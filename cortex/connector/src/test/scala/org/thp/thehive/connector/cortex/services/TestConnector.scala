//package org.thp.thehive.connector.cortex.services
//
//import akka.actor.ActorSystem
//import akka.stream.Materializer
//import org.thp.cortex.client.CortexClient
//import org.thp.scalligraph.services.config.ApplicationConfig
//
//import scala.concurrent.ExecutionContext
//
//class TestConnector(
//    client: CortexClient,
//    appConfig: ApplicationConfig,
//    mat: Materializer,
//    system: ActorSystem,
//    ec: ExecutionContext
//) extends Connector(appConfig, mat, system, ec) {
//  override def clients: Seq[CortexClient] = Seq(client)
//
//  override protected def updateHealth(): Unit = ()
//  override protected def updateStatus(): Unit = ()
//}
