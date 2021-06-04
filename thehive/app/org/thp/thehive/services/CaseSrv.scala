package org.thp.thehive.services

import akka.actor.ActorRef
import com.softwaremill.tagging.@@
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionLevel
import org.apache.tinkerpop.gremlin.process.traversal.{Order, P}
import org.apache.tinkerpop.gremlin.structure.Vertex
import org.thp.scalligraph.auth.{AuthContext, Permission}
import org.thp.scalligraph.controllers.{FFile, FPathElem}
import org.thp.scalligraph.models._
import org.thp.scalligraph.query.PropertyUpdater
import org.thp.scalligraph.services._
import org.thp.scalligraph.traversal.Converter.Identity
import org.thp.scalligraph.traversal._
import org.thp.scalligraph.{BadRequestError, EntityId, EntityIdOrName, EntityName, RichOptionTry, RichSeq}
import org.thp.thehive.controllers.v1.Conversion._
import org.thp.thehive.dto.String64
import org.thp.thehive.dto.v1.InputCustomFieldValue
import org.thp.thehive.models._
import play.api.cache.SyncCacheApi
import play.api.libs.json.{JsNull, JsObject, JsValue, Json}

import java.io.{ByteArrayInputStream, InputStream}
import java.lang.{Long => JLong}
import java.nio.file.{Files, Path}
import java.util.{List => JList, Map => JMap}
import scala.util.{Failure, Success, Try}

class CaseSrv(
    tagSrv: TagSrv,
    override val customFieldSrv: CustomFieldSrv,
    override val organisationSrv: OrganisationSrv,
    profileSrv: ProfileSrv,
    shareSrv: ShareSrv,
    taskSrv: TaskSrv,
    auditSrv: AuditSrv,
    resolutionStatusSrv: ResolutionStatusSrv,
    impactStatusSrv: ImpactStatusSrv,
    observableSrv: ObservableSrv,
    attachmentSrv: AttachmentSrv,
    userSrv: UserSrv,
    _alertSrv: => AlertSrv,
    integrityCheckActor: => ActorRef @@ IntegrityCheckTag,
    cache: SyncCacheApi
) extends VertexSrv[Case]
    with TheHiveOps {
  lazy val alertSrv: AlertSrv = _alertSrv

  val caseTagSrv              = new EdgeSrv[CaseTag, Case, Tag]
  val caseImpactStatusSrv     = new EdgeSrv[CaseImpactStatus, Case, ImpactStatus]
  val caseResolutionStatusSrv = new EdgeSrv[CaseResolutionStatus, Case, ResolutionStatus]
  val caseUserSrv             = new EdgeSrv[CaseUser, Case, User]
  val caseCustomFieldSrv      = new EdgeSrv[CaseCustomField, Case, CustomField]
  val caseCaseTemplateSrv     = new EdgeSrv[CaseCaseTemplate, Case, CaseTemplate]
  val caseProcedureSrv        = new EdgeSrv[CaseProcedure, Case, Procedure]
  val shareCaseSrv            = new EdgeSrv[ShareCase, Share, Case]
  val mergedFromSrv           = new EdgeSrv[MergedFrom, Case, Case]

  override def createEntity(e: Case)(implicit graph: Graph, authContext: AuthContext): Try[Case with Entity] =
    super.createEntity(e).map { `case` =>
      integrityCheckActor ! EntityAdded("Case")
      `case`
    }

  def create(
      `case`: Case,
      assignee: Option[User with Entity],
      organisation: Organisation with Entity,
      customFields: Seq[InputCustomFieldValue],
      caseTemplate: Option[RichCaseTemplate],
      additionalTasks: Seq[Task]
  )(implicit graph: Graph, authContext: AuthContext): Try[RichCase] = {
    val caseNumber = if (`case`.number == 0) nextCaseNumber else `case`.number
    val tagNames   = (`case`.tags ++ caseTemplate.fold[Seq[String]](Nil)(_.tags)).distinct
    for {
      tags <- tagNames.toTry(tagSrv.getOrCreate)
      createdCase <- createEntity(
        `case`.copy(
          number = caseNumber,
          assignee = assignee.map(_.login),
          organisationIds = Set(organisation._id),
          caseTemplate = caseTemplate.map(_.name),
          impactStatus = None,
          resolutionStatus = None,
          tags = tagNames
        )
      )
      _ <- assignee.map(u => caseUserSrv.create(CaseUser(), createdCase, u)).flip
      _ <- shareSrv.shareCase(owner = true, createdCase, organisation, profileSrv.orgAdmin)
      _ <- caseTemplate.map(ct => caseCaseTemplateSrv.create(CaseCaseTemplate(), createdCase, ct.caseTemplate)).flip
      _ <- caseTemplate.fold(additionalTasks)(_.tasks.map(_.task) ++ additionalTasks).toTry(task => createTask(createdCase, task))
      _ <- tags.toTry(caseTagSrv.create(CaseTag(), createdCase, _))

      caseTemplateCf =
        caseTemplate
          .fold[Seq[RichCustomField]](Seq())(_.customFields)
          .map(cf => InputCustomFieldValue(String64("customField.name", cf.name), cf.value, cf.order))
      cfs <- cleanCustomFields(caseTemplateCf, customFields).toTry {
        case InputCustomFieldValue(name, value, order) => createCustomField(createdCase, EntityIdOrName(name.value), value, order)
      }

      richCase = RichCase(createdCase, cfs, authContext.permissions)
      _ <- auditSrv.`case`.create(createdCase, richCase.toJson)
    } yield richCase
  }

  def caseId(idOrName: EntityIdOrName)(implicit graph: Graph): EntityId =
    idOrName.fold(identity, oid => cache.getOrElseUpdate(s"case-$oid")(getByName(oid)._id.getOrFail("Case").get))

  private def cleanCustomFields(caseTemplateCf: Seq[InputCustomFieldValue], caseCf: Seq[InputCustomFieldValue]): Seq[InputCustomFieldValue] = {
    val uniqueFields = caseTemplateCf.filter {
      case InputCustomFieldValue(name, _, _) => !caseCf.exists(_.name == name)
    }
    (caseCf ++ uniqueFields)
      .sortBy(cf => (cf.order.isEmpty, cf.order))
      .zipWithIndex
      .map { case (InputCustomFieldValue(name, value, _), i) => InputCustomFieldValue(name, value, Some(i)) }
  }

  def nextCaseNumber(implicit graph: Graph): Int = startTraversal.getLast.headOption.fold(0)(_.number) + 1

  override def exists(e: Case)(implicit graph: Graph): Boolean = startTraversal.getByNumber(e.number).exists

  override def update(
      traversal: Traversal.V[Case],
      propertyUpdaters: Seq[PropertyUpdater]
  )(implicit graph: Graph, authContext: AuthContext): Try[(Traversal.V[Case], JsObject)] = {
    val closeCase = PropertyUpdater(FPathElem("closeCase"), "") { (vertex, _, _) =>
      get(vertex)
        .tasks
        .or(_.has(_.status, TaskStatus.Waiting), _.has(_.status, TaskStatus.InProgress))
        .toIterator
        .toTry {
          case task if task.status == TaskStatus.InProgress => taskSrv.updateStatus(task, TaskStatus.Completed)
          case task                                         => taskSrv.updateStatus(task, TaskStatus.Cancel)
        }
        .flatMap { _ =>
          vertex.property("endDate", System.currentTimeMillis())
          Success(Json.obj("endDate" -> System.currentTimeMillis()))
        }
    }

    val isCloseCase = propertyUpdaters.exists(p => p.path.matches(FPathElem("status")) && p.value == CaseStatus.Resolved)

    val newPropertyUpdaters = if (isCloseCase) closeCase +: propertyUpdaters else propertyUpdaters
    auditSrv.mergeAudits(super.update(traversal, newPropertyUpdaters)) {
      case (caseSteps, updatedFields) =>
        caseSteps
          .clone()
          .getOrFail("Case")
          .flatMap(auditSrv.`case`.update(_, updatedFields))
    }
  }

  def updateTags(`case`: Case with Entity, tags: Set[String])(implicit
      graph: Graph,
      authContext: AuthContext
  ): Try[(Seq[Tag with Entity], Seq[Tag with Entity])] =
    for {
      tagsToAdd <- (tags -- `case`.tags).toTry(tagSrv.getOrCreate)
      tagsToRemove = get(`case`).tags.toSeq.filterNot(t => tags.contains(t.toString))
      _ <- tagsToAdd.toTry(caseTagSrv.create(CaseTag(), `case`, _))
      _ = if (tagsToRemove.nonEmpty) get(`case`).outE[CaseTag].filter(_.otherV().hasId(tagsToRemove.map(_._id): _*)).remove()
      _ <- get(`case`).update(_.tags, tags.toSeq).getOrFail("Case")
      _ <- auditSrv.`case`.update(`case`, Json.obj("tags" -> tags))
    } yield (tagsToAdd, tagsToRemove)

  def addTags(`case`: Case with Entity, tags: Set[String])(implicit graph: Graph, authContext: AuthContext): Try[Unit] =
    updateTags(`case`, tags ++ `case`.tags).map(_ => ())

  def createTask(`case`: Case with Entity, task: Task)(implicit graph: Graph, authContext: AuthContext): Try[RichTask] =
    for {
      assignee <- task.assignee.map(u => get(`case`).assignableUsers.getByName(u).getOrFail("User")).flip
      task     <- taskSrv.create(task.copy(relatedId = `case`._id, organisationIds = Set(organisationSrv.currentId)), assignee)
      _        <- shareSrv.shareTask(task, `case`, organisationSrv.currentId)
    } yield task

  def createObservable(`case`: Case with Entity, observable: Observable, data: String)(implicit
      graph: Graph,
      authContext: AuthContext
  ): Try[RichObservable] =
    for {
      createdObservable <- observableSrv.create(observable.copy(organisationIds = Set(organisationSrv.currentId), relatedId = `case`._id), data)
      _                 <- shareSrv.shareObservable(createdObservable, `case`, organisationSrv.currentId)
    } yield createdObservable

  def createObservable(`case`: Case with Entity, observable: Observable, attachment: Attachment with Entity)(implicit
      graph: Graph,
      authContext: AuthContext
  ): Try[RichObservable] =
    for {
      createdObservable <- observableSrv.create(observable.copy(organisationIds = Set(organisationSrv.currentId), relatedId = `case`._id), attachment)
      _                 <- shareSrv.shareObservable(createdObservable, `case`, organisationSrv.currentId)
    } yield createdObservable

  def createObservable(`case`: Case with Entity, observable: Observable, file: FFile)(implicit
      graph: Graph,
      authContext: AuthContext
  ): Try[RichObservable] =
    attachmentSrv.create(file).flatMap(attachment => createObservable(`case`, observable, attachment))

  override def delete(`case`: Case with Entity)(implicit graph: Graph, authContext: AuthContext): Try[Unit] = {
    val details = Json.obj("number" -> `case`.number, "title" -> `case`.title)
    organisationSrv.get(authContext.organisation).getOrFail("Organisation").flatMap { organisation =>
      shareSrv
        .get(`case`, authContext.organisation)
        .getOrFail("Share")
        .flatMap {
          case share if share.owner =>
            get(`case`).shares.toSeq.toTry(s => shareSrv.unshareCase(s._id)).map(_ => get(`case`).remove())
          case _ =>
            throw BadRequestError("Your organisation must be owner of the case")
          // shareSrv.unshareCase(share._id)
        }
        .map(_ => auditSrv.`case`.delete(`case`, organisation, Some(details)))
    }
  }

  override def getByName(name: String)(implicit graph: Graph): Traversal.V[Case] =
    Try(startTraversal.getByNumber(name.toInt)).getOrElse(startTraversal.empty)

  def getCustomField(`case`: Case with Entity, customFieldIdOrName: EntityIdOrName)(implicit graph: Graph): Option[RichCustomField] =
    get(`case`).customFieldValue(customFieldIdOrName).richCustomField.headOption

  def updateCustomField(
      `case`: Case with Entity,
      customFieldValues: Seq[(CustomField, Any, Option[Int])]
  )(implicit graph: Graph, authContext: AuthContext): Try[Unit] = {
    val customFieldNames = customFieldValues.map(_._1.name)
    get(`case`)
      .richCustomFields
      .toIterator
      .filterNot(rcf => customFieldNames.contains(rcf.name))
      .foreach(rcf => get(`case`).customFieldValue(EntityName(rcf.name)).remove())
    customFieldValues
      .toTry { case (cf, v, o) => setOrCreateCustomField(`case`, EntityName(cf.name), Some(v), o) }
      .map(_ => ())
  }

  def setOrCreateCustomField(`case`: Case with Entity, customFieldIdOrName: EntityIdOrName, value: Option[Any], order: Option[Int])(implicit
      graph: Graph,
      authContext: AuthContext
  ): Try[Unit] = {
    val cfv = get(`case`).customFieldValue(customFieldIdOrName)
    if (cfv.clone().exists)
      cfv.setValue(value)
    else
      createCustomField(`case`, customFieldIdOrName, value, order).map(_ => ())
  }

  def createCustomField(
      `case`: Case with Entity,
      customFieldIdOrName: EntityIdOrName,
      customFieldValue: Option[Any],
      order: Option[Int]
  )(implicit graph: Graph, authContext: AuthContext): Try[RichCustomField] =
    for {
      cf   <- customFieldSrv.getOrFail(customFieldIdOrName)
      ccf  <- CustomFieldType.map(cf.`type`).setValue(CaseCustomField().order_=(order), customFieldValue)
      ccfe <- caseCustomFieldSrv.create(ccf, `case`, cf)
    } yield RichCustomField(cf, ccfe)

  def deleteCustomField(
      cfIdOrName: EntityIdOrName
  )(implicit graph: Graph, authContext: AuthContext): Try[Unit] =
    Try(
      caseCustomFieldSrv
        .get(cfIdOrName)
        .filter(_.outV.v[Case])
        .remove()
    )

  def setImpactStatus(
      `case`: Case with Entity,
      impactStatus: String
  )(implicit graph: Graph, authContext: AuthContext): Try[Unit] =
    impactStatusSrv.getOrFail(EntityIdOrName(impactStatus)).flatMap(setImpactStatus(`case`, _))

  def setImpactStatus(
      `case`: Case with Entity,
      impactStatus: ImpactStatus with Entity
  )(implicit graph: Graph, authContext: AuthContext): Try[Unit] = {
    get(`case`).update(_.impactStatus, Some(impactStatus.value)).outE[CaseImpactStatus].remove()
    caseImpactStatusSrv.create(CaseImpactStatus(), `case`, impactStatus)
    auditSrv.`case`.update(`case`, Json.obj("impactStatus" -> impactStatus.value))
  }

  def unsetImpactStatus(`case`: Case with Entity)(implicit graph: Graph, authContext: AuthContext): Try[Unit] = {
    get(`case`).update(_.impactStatus, None).outE[CaseImpactStatus].remove()
    auditSrv.`case`.update(`case`, Json.obj("impactStatus" -> JsNull))
  }

  def setResolutionStatus(
      `case`: Case with Entity,
      resolutionStatus: String
  )(implicit graph: Graph, authContext: AuthContext): Try[Unit] =
    resolutionStatusSrv.getOrFail(EntityIdOrName(resolutionStatus)).flatMap(setResolutionStatus(`case`, _))

  def setResolutionStatus(
      `case`: Case with Entity,
      resolutionStatus: ResolutionStatus with Entity
  )(implicit graph: Graph, authContext: AuthContext): Try[Unit] = {
    get(`case`).update(_.resolutionStatus, Some(resolutionStatus.value)).outE[CaseResolutionStatus].remove()
    caseResolutionStatusSrv.create(CaseResolutionStatus(), `case`, resolutionStatus)
    auditSrv.`case`.update(`case`, Json.obj("resolutionStatus" -> resolutionStatus.value))
  }

  def unsetResolutionStatus(`case`: Case with Entity)(implicit graph: Graph, authContext: AuthContext): Try[Unit] = {
    get(`case`).update(_.resolutionStatus, None).outE[CaseResolutionStatus].remove()
    auditSrv.`case`.update(`case`, Json.obj("resolutionStatus" -> JsNull))
  }

  def assign(`case`: Case with Entity, user: User with Entity)(implicit graph: Graph, authContext: AuthContext): Try[Unit] = {
    get(`case`).update(_.assignee, Some(user.login)).outE[CaseUser].remove()
    caseUserSrv.create(CaseUser(), `case`, user)
    auditSrv.`case`.update(`case`, Json.obj("owner" -> user.login))
  }

  def unassign(`case`: Case with Entity)(implicit graph: Graph, authContext: AuthContext): Try[Unit] = {
    get(`case`).update(_.assignee, None).outE[CaseUser].remove()
    auditSrv.`case`.update(`case`, Json.obj("owner" -> JsNull))
  }

  def merge(cases: Seq[Case with Entity])(implicit graph: Graph, authContext: AuthContext): Try[RichCase] =
    if (cases.size > 1 && canMerge(cases)) {
      val mergedCase = Case(
        cases.map(_.title).mkString(" / "),
        cases.map(_.description).mkString("\n\n"),
        cases.map(_.severity).max,
        cases.map(_.startDate).min,
        None,
        cases.exists(_.flag),
        cases.map(_.tlp).max,
        cases.map(_.pap).max,
        CaseStatus.Open,
        cases.map(_.summary).fold(None)((s1, s2) => (s1 ++ s2).reduceOption(_ + "\n\n" + _)),
        cases.flatMap(_.tags).distinct
      )

      val allProfilesOrgas: Seq[(Profile with Entity, Organisation with Entity)] = get(cases.head)
        .shares
        .project(_.by(_.profile).by(_.organisation))
        .toSeq

      for {
        user        <- userSrv.current.getOrFail("User")
        currentOrga <- organisationSrv.current.getOrFail("Organisation")
        richCase    <- create(mergedCase, Some(user), currentOrga, Seq(), None, Seq())
        // Share case with all organisations except the one who created the merged case
        _ <-
          allProfilesOrgas
            .filterNot(_._2._id == currentOrga._id)
            .toTry(profileOrg => shareSrv.shareCase(owner = false, richCase.`case`, profileOrg._2, profileOrg._1))
        _ <- cases.toTry { c =>
          for {

            _ <- shareMergedCaseTasks(allProfilesOrgas.map(_._2), c, richCase.`case`)
            _ <- shareMergedCaseObservables(allProfilesOrgas.map(_._2), c, richCase.`case`)
            _ <-
              get(c)
                .alert
                .update(_.caseId, richCase._id)
                .toSeq
                .toTry(alertSrv.alertCaseSrv.create(AlertCase(), _, richCase.`case`))
            _ <-
              get(c)
                .procedure
                .toSeq
                .toTry(caseProcedureSrv.create(CaseProcedure(), richCase.`case`, _))
            _ <-
              get(c)
                .richCustomFields
                .toSeq
                .toTry(c => createCustomField(richCase.`case`, EntityIdOrName(c.customField.name), c.value, c.order))
          } yield Success(())
        }
        _ <- cases.toTry(super.delete(_))
      } yield richCase
    } else
      Failure(BadRequestError("To be able to merge, cases must have same organisation / profile pair and user must be org-admin"))

  def exportAsZip(richCase: RichCase, observables: Seq[RichObservable], tasks: Seq[RichTask]): Path = {
    val file = Files.createTempFile(s"export-case-${richCase.number}", "zip")
    Files.delete(file)
    val zipFile = new ZipFile(file.toFile)
    def writeInZip(data: InputStream, filename: String): Unit = {
      val zipParams = new ZipParameters
      zipParams.setCompressionLevel(CompressionLevel.FASTEST)
      zipParams.setFileNameInZip(filename)
      zipFile.addStream(data, zipParams)
    }
    def jsonToInputStream(json: JsValue): InputStream = new ByteArrayInputStream(Json.prettyPrint(json).getBytes)

    writeInZip(jsonToInputStream(richCase.toJson), "case.json")

    observables.foreach { observable =>
      writeInZip(jsonToInputStream(observable.toJson), s"observables/${observable._id}.json")
      observable.attachment.foreach { attachement =>
        val stream = attachmentSrv.stream(attachement)
        writeInZip(stream, s"observables/${observable._id}/${attachement.name}")
      }
    }

    tasks.foreach { task =>
      writeInZip(jsonToInputStream(task.toJson), s"tasks/task-${task.order}.json")
    }

    file
  }

  private def canMerge(cases: Seq[Case with Entity])(implicit graph: Graph, authContext: AuthContext): Boolean = {
    val allOrgProfiles = getByIds(cases.map(_._id): _*)
      .flatMap(_.shares.project(_.by(_.profile.value(_.name)).by(_.organisation._id)).fold)
      .toSeq
      .map(_.toSet)
      .distinct

    // All cases must have the same organisation / profile pair &&
    // case organisation must match current organisation and be of org-admin profile
    allOrgProfiles.size == 1 && allOrgProfiles
      .head
      .exists {
        case (profile, orgId) => orgId == organisationSrv.currentId && profile == Profile.orgAdmin.name
      }
  }

  private def shareMergedCaseTasks(orgs: Seq[Organisation with Entity], fromCase: Case with Entity, mergedCase: Case with Entity)(implicit
      graph: Graph,
      authContext: AuthContext
  ): Try[Unit] =
    orgs
      .toTry { org =>
        get(fromCase)
          .share(org._id)
          .tasks
          .update(_.relatedId, mergedCase._id)
          .richTask
          .toSeq
          .toTry(shareSrv.shareTask(_, mergedCase, org._id))
      }
      .map(_ => ())

  private def shareMergedCaseObservables(orgs: Seq[Organisation with Entity], fromCase: Case with Entity, mergedCase: Case with Entity)(implicit
      graph: Graph,
      authContext: AuthContext
  ): Try[Unit] =
    orgs
      .toTry { org =>
        get(fromCase)
          .share(org._id)
          .observables
          .update(_.relatedId, mergedCase._id)
          .richObservable
          .toSeq
          .toTry(shareSrv.shareObservable(_, mergedCase, org._id))
      }
      .map(_ => ())
}

trait CaseOpsNoDeps { _: TheHiveOpsNoDeps =>

  implicit class CaseOpsNoDepsDefs(val traversal: Traversal.V[Case]) extends EntityWithCustomFieldOpsNoDepsDefs[Case, CaseCustomField] {

    def resolutionStatus: Traversal.V[ResolutionStatus] = traversal.out[CaseResolutionStatus].v[ResolutionStatus]

    def get(idOrName: EntityIdOrName): Traversal.V[Case] =
      idOrName.fold(traversal.getByIds(_), n => getByNumber(n.toInt))

    def getByNumber(caseNumber: Int): Traversal.V[Case] = traversal.has(_.number, caseNumber)

    def assignee: Traversal.V[User] = traversal.out[CaseUser].v[User]

    def assignedTo(userLogin: String*): Traversal.V[Case] =
      if (userLogin.isEmpty) traversal.empty
      else if (userLogin.size == 1) traversal.has(_.assignee, userLogin.head)
      else traversal.has(_.assignee, P.within(userLogin: _*))

    def caseTemplate: Traversal.V[CaseTemplate] = traversal.out[CaseCaseTemplate].v[CaseTemplate]

    def can(permission: Permission)(implicit authContext: AuthContext): Traversal.V[Case] =
      if (authContext.permissions.contains(permission))
        traversal.filter(_.share.profile.has(_.permissions, permission))
      else
        traversal.empty

    def getLast: Traversal.V[Case] =
      traversal.sort(_.by("number", Order.desc)).limit(1)

    def richCaseWithCustomRenderer[D, G, C <: Converter[D, G]](
        entityRenderer: Traversal.V[Case] => Traversal[D, G, C]
    )(implicit authContext: AuthContext): Traversal[(RichCase, D), JMap[String, Any], Converter[(RichCase, D), JMap[String, Any]]] =
      traversal
        .project(
          _.by
            .by(_.richCustomFields.fold)
            .by(entityRenderer)
            .by(_.userPermissions)
        )
        .domainMap {
          case (caze, customFields, renderedEntity, userPermissions) =>
            RichCase(
              caze,
              customFields,
              userPermissions
            ) -> renderedEntity
        }

    def share(implicit authContext: AuthContext): Traversal.V[Share] = share(authContext.organisation)

    def share(organisation: EntityIdOrName): Traversal.V[Share] =
      shares.filter(_.organisation.get(organisation)).v[Share]

    def shares: Traversal.V[Share] = traversal.in[ShareCase].v[Share]

    def organisations: Traversal.V[Organisation] = traversal.in[ShareCase].in[OrganisationShare].v[Organisation]

    def organisations(permission: Permission): Traversal.V[Organisation] =
      shares.filter(_.profile.has(_.permissions, permission)).organisation

    def userPermissions(implicit authContext: AuthContext): Traversal[Set[Permission], JList[String], Converter[Set[Permission], JList[String]]] =
      traversal
        .share(authContext.organisation)
        .profile
        .value(_.permissions)
        .fold
        .domainMap(_.toSet & authContext.permissions)

    def origin: Traversal.V[Organisation] = shares.has(_.owner, true).organisation

//    def audits(organisationSrv: OrganisationSrv)(implicit authContext: AuthContext): Traversal.V[Audit] =
//      traversal
//        .unionFlat(_.visible(organisationSrv), _.observables(organisationIdOrName), _.tasks(organisationIdOrName), _.share(organisationIdOrName))
//        .in[AuditContext]
//        .v[Audit]

    def linkedCases(implicit authContext: AuthContext): Seq[(RichCase, Seq[RichObservable])] = {
      val originCaseLabel = StepLabel.v[Case]
      val observableLabel = StepLabel.v[Observable] // TODO add similarity on attachment
      traversal
        .as(originCaseLabel)
        .observables
        .hasNot(_.ignoreSimilarity, true)
        .as(observableLabel)
        .data
        .observables
        .hasNot(_.ignoreSimilarity, true)
        .shares
        .filter(_.organisation.current)
        .`case`
        .where(P.neq(originCaseLabel.name))
        .group(_.by, _.by(_.select(observableLabel).richObservable.fold))
        .unfold
        .project(_.by(_.selectKeys.richCase).by(_.selectValues))
        .toSeq
    }

    def isShared: Traversal[Boolean, Boolean, Identity[Boolean]] =
      traversal.choose(_.inE[ShareCase].count.is(P.gt(1)), true, false)

    def richCase(implicit authContext: AuthContext): Traversal[RichCase, JMap[String, Any], Converter[RichCase, JMap[String, Any]]] =
      traversal
        .project(
          _.by
            .by(_.richCustomFields.fold)
            .by(_.userPermissions)
        )
        .domainMap {
          case (caze, customFields, userPermissions) =>
            RichCase(
              caze,
              customFields,
              userPermissions
            )
        }

    def user: Traversal.V[User] = traversal.out[CaseUser].v[User]

    def richCaseWithoutPerms: Traversal[RichCase, JMap[String, Any], Converter[RichCase, JMap[String, Any]]] =
      traversal
        .project(
          _.by
            .by(_.richCustomFields.fold)
        )
        .domainMap {
          case (caze, customFields) =>
            RichCase(
              caze,
              customFields,
              Set.empty
            )
        }

    def tags: Traversal.V[Tag] = traversal.out[CaseTag].v[Tag]

    def impactStatus: Traversal.V[ImpactStatus] = traversal.out[CaseImpactStatus].v[ImpactStatus]

    def tasks(implicit authContext: AuthContext): Traversal.V[Task] = tasks(authContext.organisation)

    def tasks(organisationIdOrName: EntityIdOrName): Traversal.V[Task] =
      share(organisationIdOrName).tasks

    def observables(implicit authContext: AuthContext): Traversal.V[Observable] = observables(authContext.organisation)

    def observables(organisationIdOrName: EntityIdOrName): Traversal.V[Observable] =
      share(organisationIdOrName).observables

    def assignableUsers(implicit authContext: AuthContext): Traversal.V[User] =
      organisations(Permissions.manageCase)
        .visible
        .users(Permissions.manageCase)
        .dedup

    def alert: Traversal.V[Alert] = traversal.in[AlertCase].v[Alert]

    def procedure: Traversal.V[Procedure] = traversal.out[CaseProcedure].v[Procedure]

    def isActionRequired(implicit authContext: AuthContext): Traversal[Boolean, Boolean, Converter.Identity[Boolean]] =
      traversal.choose(_.share(authContext).outE[ShareTask].has(_.actionRequired, true), true, false)

    def handlingDuration: Traversal[Long, Long, IdentityConverter[Long]] =
      traversal.coalesceIdent(
        _.has(_.endDate)
          .sack(
            (_: JLong, importDate: JLong) => importDate,
            _.by(_.value(_.endDate).graphMap[Long, JLong, Converter[Long, JLong]](_.getTime, Converter.long))
          )
          .sack((_: Long) - (_: JLong), _.by(_._createdAt.graphMap[Long, JLong, Converter[Long, JLong]](_.getTime, Converter.long)))
          .sack[Long],
        _.constant(0L)
      )

    def customFields: Traversal.E[CaseCustomField] = traversal.outE[CaseCustomField]
  }

  implicit class CaseCustomFieldOpsDef(traversal: Traversal.E[CaseCustomField]) extends CustomFieldValueOpsDefs[CaseCustomField](traversal)
}

trait CaseOps { caseOps: TheHiveOps =>

  protected val organisationSrv: OrganisationSrv
  protected val customFieldSrv: CustomFieldSrv

  implicit class CaseOpsDefs(val traversal: Traversal.V[Case]) extends EntityWithCustomFieldOpsDefs[Case, CaseCustomField] {
    override protected val customFieldSrv: CustomFieldSrv = caseOps.customFieldSrv
    override def selectCustomField(traversal: Traversal.V[Case]): Traversal.E[CaseCustomField] =
      traversal.outE[CaseCustomField]
    def visible(implicit authContext: AuthContext): Traversal.V[Case] =
      traversal.has(_.organisationIds, organisationSrv.currentId(traversal.graph, authContext))
  }
}

class CaseIntegrityCheckOps(val db: Database, val service: CaseSrv, userSrv: UserSrv, caseTemplateSrv: CaseTemplateSrv)
    extends IntegrityCheckOps[Case]
    with TheHiveOpsNoDeps {
  def removeDuplicates(): Unit =
    findDuplicates()
      .foreach { entities =>
        db.tryTransaction { implicit graph =>
          resolve(entities)
        }
      }

  override def resolve(entities: Seq[Case with Entity])(implicit graph: Graph): Try[Unit] = {
    val nextNumber = service.nextCaseNumber
    firstCreatedEntity(entities).foreach(
      _._2
        .flatMap(service.get(_).setConverter[Vertex, Converter.Identity[Vertex]](Converter.identity).headOption)
        .zipWithIndex
        .foreach {
          case (vertex, index) =>
            UMapping.int.setProperty(vertex, "number", nextNumber + index)
        }
    )
    Success(())
  }

  private def organisationCheck(`case`: Case with Entity, organisationIds: Set[EntityId])(implicit graph: Graph): Seq[String] =
    if (`case`.organisationIds == organisationIds) Nil
    else {
      service.get(`case`).update(_.organisationIds, organisationIds).iterate()
      Seq("invalidOrganisationIds")
    }

  private def assigneeCheck(`case`: Case with Entity, assignees: Seq[String])(implicit graph: Graph, authContext: AuthContext): Seq[String] =
    `case`.assignee match {
      case None if assignees.isEmpty      => Nil
      case Some(a) if assignees == Seq(a) => Nil
      case None if assignees.size == 1 =>
        service.get(`case`).update(_.assignee, assignees.headOption).iterate()
        Seq("invalidAssigneeLink")
      case Some(a) if assignees.isEmpty =>
        userSrv.getByName(a).getOrFail("User") match {
          case Success(user) =>
            service.caseUserSrv.create(CaseUser(), `case`, user)
            Seq("missingAssigneeLink")
          case _ =>
            service.get(`case`).update(_.assignee, None).iterate()
            Seq("invalidAssignee")
        }
      case None if assignees.toSet.size == 1 =>
        service.get(`case`).update(_.assignee, assignees.headOption).flatMap(_.outE[CaseUser].range(1, 100)).remove()
        Seq("multiAssignment")
      case _ =>
        service.get(`case`).flatMap(_.outE[CaseUser].sort(_.by("_createdAt", Order.desc)).range(1, 100)).remove()
        service.get(`case`).update(_.assignee, service.get(`case`).assignee.value(_.login).headOption).iterate()
        Seq("incoherentAssignee")
    }

  def caseTemplateCheck(`case`: Case with Entity, caseTemplates: Seq[String])(implicit graph: Graph, authContext: AuthContext): Seq[String] =
    `case`.caseTemplate match {
      case None if caseTemplates.isEmpty        => Nil
      case Some(ct) if caseTemplates == Seq(ct) => Nil
      case None if caseTemplates.size == 1 =>
        service.get(`case`).update(_.caseTemplate, caseTemplates.headOption).iterate()
        Seq("invalidCaseTemplateLink")
      case Some(ct) if caseTemplates.isEmpty =>
        caseTemplateSrv.getByName(ct).getOrFail("User") match {
          case Success(caseTemplate) =>
            service.caseCaseTemplateSrv.create(CaseCaseTemplate(), `case`, caseTemplate)
            Seq("missingCaseTemplateLink")
          case _ =>
            service.get(`case`).update(_.caseTemplate, None).iterate()
            Seq("invalidCaseTemplate")
        }
      case None if caseTemplates.toSet.size == 1 =>
        service.get(`case`).update(_.caseTemplate, caseTemplates.headOption).flatMap(_.outE[CaseCaseTemplate].range(1, 100)).remove()
        Seq("multiCaseTemplate")
      case _ =>
        service.get(`case`).flatMap(_.outE[CaseCaseTemplate].sort(_.by("_createdAt", Order.asc)).range(1, 100)).remove()
        service.get(`case`).update(_.caseTemplate, service.get(`case`).caseTemplate.value(_.name).headOption).iterate()
        Seq("incoherentCaseTemplate")
    }
  override def globalCheck(): Map[String, Long] = {
    implicit val authContext: AuthContext = LocalUserSrv.getSystemAuthContext

    db.tryTransaction { implicit graph =>
      Try {
        service
          .startTraversal
          .project(
            _.by
              .by(_.organisations._id.fold)
              .by(_.assignee.value(_.login).fold)
              .by(_.caseTemplate.value(_.name).fold)
          )
          .toIterator
          .flatMap {
            case (case0, organisationIds, assigneeIds, caseTemplateNames) if organisationIds.nonEmpty =>
              organisationCheck(case0, organisationIds.toSet) ++ assigneeCheck(case0, assigneeIds) ++ caseTemplateCheck(case0, caseTemplateNames)
            case (case0, _, _, _) =>
              service.get(case0).remove()
              Seq("orphan")
          }
          .toSeq
      }
    }.getOrElse(Seq("globalFailure"))
      .groupBy(identity)
      .view
      .mapValues(_.size.toLong)
      .toMap
  }
}
