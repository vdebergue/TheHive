package org.thp.thehive.services.notification.notifiers

import java.util.{HashMap => JHashMap}

import scala.collection.JavaConverters._

import play.api.test.PlaySpecification

import org.thp.scalligraph.auth.AuthContext
import org.thp.scalligraph.models.{Database, DummyUserSrv}
import org.thp.scalligraph.steps.StepsOps._
import org.thp.thehive.TestAppBuilder
import org.thp.thehive.services.{AuditSrv, CaseSrv, UserSrv}

class NotificationTemplateTest extends PlaySpecification with TestAppBuilder {
  implicit val authContext: AuthContext = DummyUserSrv(userId = "certuser@thehive.local").authContext
  val templateEngine: Template          = new Object with Template {}

  "template engine" should {
    "format message" in {
      val template =
        """Dear {{user.name}},
          |you have a new notification:
          |
          |The {{audit.objectType}} {{audit.objectId}} has been {{audit.action}}d by {{audit._createdBy}}
          |
          |{{#with object~}}
          |  {{#if (eq _type "Case")}}
          |    {{~title}}
          |  {{else}}
          |    {{~_type}} is not a case
          |  {{/if}}
          |{{~/with}}
          |
          |Audit ({{audit.requestId}}): {{audit.action}} {{audit.objectType}} {{audit.objectId}} by {{audit._createdBy}}
          |Context {{context._id}}""".stripMargin

      val model = new JHashMap[String, AnyRef]
      model.put(
        "audit",
        Map("objectType" -> "Case", "objectId" -> "2231", "action" -> "create", "_createdBy" -> "admin@thehive.local", "requestId" -> "testRequest").asJava
      )
      model.put("object", Map("_type" -> "Case", "title" -> "case title").asJava)
      model.put("user", Map("name"    -> "Thomas").asJava)
      model.put("context", Map("_id"  -> "2231").asJava)
      val message = templateEngine.handlebars.compileInline(template).apply(model)
      message must beEqualTo("""Dear Thomas,
                               |you have a new notification:
                               |
                               |The Case 2231 has been created by admin@thehive.local
                               |
                               |case title
                               |  
                               |
                               |Audit (testRequest): create Case 2231 by admin@thehive.local
                               |Context 2231""".stripMargin)
    }

    "build properly message" in testApp { app =>
      val template =
        """Dear {{user.name}},
          |you have a new notification:
          |
          |The {{audit.objectType}} {{audit.objectId}} has been {{audit.action}}d by {{audit._createdBy}}
          |
          |{{#with object~}}
          |  {{#if (eq _type "Case")}}
          |    {{~title}}
          |  {{else}}
          |    {{~_type}} is not a case
          |  {{/if}}
          |{{~/with}}
          |
          |Audit ({{audit.requestId}}): {{audit.action}} {{audit.objectType}} {{audit.objectId}} by {{audit._createdBy}}
          |Context {{context._id}}""".stripMargin

      val message = app[Database].tryTransaction { implicit graph =>
        for {
          case4 <- app[CaseSrv].get("#1").getOrFail()
          _     <- app[CaseSrv].addTags(case4, Set("emailer test"))
          _     <- app[CaseSrv].addTags(case4, Set("emailer test")) // this is needed to make AuditSrv write Audit in DB
          audit <- app[AuditSrv].initSteps.has("objectId", case4._id).getOrFail()
          user  <- app[UserSrv].get("certuser@thehive.local").getOrFail()
          msg   <- templateEngine.buildMessage(template, audit, Some(case4), Some(case4), user, "http://localhost/")
        } yield msg
      }
      message must beSuccessfulTry.which { m =>
        m must beMatching("""Dear certuser,
                            |you have a new notification:
                            |
                            |The Case \d+ has been updated by certuser@thehive.local
                            |
                            |case#1
                            |  
                            |
                            |Audit \(testRequest\): update Case \d+ by certuser@thehive.local
                            |Context \d+""".stripMargin)
      }
    }
  }
}