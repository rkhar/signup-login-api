package kz.anon.portal

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import com.sksamuel.elastic4s.http.JavaClient
import com.sksamuel.elastic4s.{ElasticClient, ElasticProperties}
import com.sksamuel.elastic4s.ElasticDsl._
import com.typesafe.config.ConfigFactory
import kz.anon.portal.mainapi.MainApi
import kz.anon.portal.service.{ElasticFunctionality, MainActor}

import scala.concurrent.ExecutionContextExecutor
import scala.util.{Failure, Success}

object Boot {

  private def startHttpServer(routes: Route, system: ActorSystem[_]): Unit = {
    implicit val classicSystem: akka.actor.ActorSystem = system.toClassic
    import system.executionContext

    val futureBinding = Http().bindAndHandle(routes, "localhost", 8080)
    futureBinding.onComplete {
      case Success(binding) =>
        val address = binding.localAddress
        system.log.info("Server online at http://{}:{}/", address.getHostString, address.getPort)
      case Failure(ex) =>
        system.log.error("Failed to bind HTTP endpoint, terminating system", ex)
        system.terminate()
    }
  }

  def main(args: Array[String]): Unit = {

    //    ---------- ElasticSearch connection creation ----------

    val config = ConfigFactory.load()

    val usersIndex: String     = config.getString("elastic.indexes.users")
    val documentsIndex: String = config.getString("elastic.indexes.documents")
    val elasticHosts: String   = config.getString("elastic.hosts")
    val elasticPorts: String   = config.getString("elastic.ports")
    val elasticClient: ElasticClient = ElasticClient(
      JavaClient(ElasticProperties(s"http://$elasticHosts:$elasticPorts"))
    )
    //    ---------- ElasticSearch connection creation ----------

    //    ---------- server-bootstrapping ----------

    val rootBehavior = Behaviors.setup[Nothing] { context =>
      implicit val executionContext: ExecutionContextExecutor = context.executionContext

      val elasticFuncs = new ElasticFunctionality(elasticClient, usersIndex, documentsIndex)

      val authActor =
        context.spawn(MainActor(elasticFuncs, elasticClient, usersIndex), "UserRegistryActor")
      context.watch(authActor)

      val routes = new MainApi(authActor)(context.system)
      startHttpServer(routes.mainRoutes, context.system)

      Behaviors.empty
    }

    val system = ActorSystem[Nothing](rootBehavior, "LocalServer")
    //    ---------- server-bootstrapping ----------

    if (!elasticClient.execute(indexExists(usersIndex)).await.result.isExists)
      elasticClient.execute(createIndex(usersIndex))
    else system.log.info(s"$usersIndex already exists")

    if (!elasticClient.execute(indexExists(documentsIndex)).await.result.isExists)
      elasticClient.execute(createIndex(documentsIndex))
    else system.log.info(s"$documentsIndex already exists")
  }

}
