package com.couchbase.client.scala


import java.util.concurrent.{CompletableFuture, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

import com.couchbase.client.core.Core
import com.couchbase.client.core.env.Credentials
import com.couchbase.client.core.msg.kv.ObserveViaCasRequest
import com.couchbase.client.core.msg.query.{QueryAdditionalBasic, QueryRequest, QueryResponse}
import com.couchbase.client.scala.api.QueryOptions
import com.couchbase.client.scala.env.ClusterEnvironment
import com.couchbase.client.scala.json.JsonObject
import com.couchbase.client.scala.query._
import com.couchbase.client.scala.util.AsyncUtils.DefaultTimeout
import com.couchbase.client.scala.util.{FutureConversions, Validate}
import com.couchbase.client.core.deps.io.netty.util.CharsetUtil
import com.couchbase.client.core.deps.io.netty.util.CharsetUtil
import com.couchbase.client.core.error.QueryServiceException
import reactor.core.scala.publisher.{Flux, Mono}

import scala.compat.java8.FutureConverters
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import collection.JavaConverters._
import scala.compat.java8.OptionConverters._

object DurationConversions {
  implicit def scalaDurationToJava(in: scala.concurrent.duration.Duration): java.time.Duration = {
    java.time.Duration.ofNanos(in.toNanos)
  }

  implicit def javaDurationToScala(in: java.time.Duration): scala.concurrent.duration.Duration = {
    scala.concurrent.duration.Duration(in.toNanos, TimeUnit.NANOSECONDS)
  }

}

class AsyncCluster(environment: => ClusterEnvironment)
                  (implicit ec: ExecutionContext) {
  private[scala] val core = Core.create(environment)
  private[scala] val env = environment

  // Opening resources will not raise errors, instead they will be deferred until later
  private[scala] var deferredError: Option[RuntimeException] = None

  private[scala] val queryHandler = new QueryHandler()


  def bucket(name: String): Future[AsyncBucket] = {
    FutureConversions.javaMonoToScalaFuture(core.openBucket(name))
      .map(v => new AsyncBucket(name, core, environment))
  }

  def query(statement: String, options: QueryOptions): Future[QueryResult] = {

    queryHandler.request(statement, options, core, environment) match {
      case Success(request) =>
        core.send(request)

        import reactor.core.publisher.{Mono => JavaMono}
        import reactor.core.scala.publisher.{Mono => ScalaMono}

        val javaMono = JavaMono.fromFuture(request.response())

        // Wait until rows and others are done
        // If rows failed with an error, return Future.failed
        // Else return Future(QueryResult)

        val out: JavaMono[QueryResult] = javaMono
          .flatMap(response => {

            val rowsKeeper = new AtomicReference[java.util.List[Array[Byte]]]()

            val ret: JavaMono[QueryResult] = response.rows
              .collectList()
              .flatMap(rows => {
                rowsKeeper.set(rows)

                response.additional()
              })
              .map(addl => {
                val rows = rowsKeeper.get().asScala.map(QueryRow)

                val result = QueryResult(
                  rows,
                  response.requestId(),
                  response.clientContextId().asScala,
                  QuerySignature(response.signature().asScala),
                  QueryAdditional(null, null, null, null)
                )

                result
              })

            ret
          })
          .onErrorResume(err => {
            err match {
              case e: QueryServiceException => JavaMono.error(QueryError(e.content))
              case _ => JavaMono.error(err)
            }
          })

        val future: CompletableFuture[QueryResult] = out.toFuture

        val ret = FutureConversions.javaCFToScalaFuture(future)

        ret.failed.foreach(err => {
          println(s"scala future error ${err}")
        })

        ret


      case Failure(err) => Future.failed(err)
    }
  }

  def shutdown(): Future[Unit] = {
    Future {
      environment.shutdown(environment.timeoutConfig().disconnectTimeout())
    }
  }
}

object AsyncCluster {
  private implicit val ec = Cluster.ec

  def connect(connectionString: String, username: String, password: String): Future[AsyncCluster] = {
    Future {
      Cluster.connect(connectionString, username, password).async
    }
  }

  def connect(connectionString: String, credentials: Credentials): Future[AsyncCluster] = {
    Future {
      Cluster.connect(connectionString, credentials).async
    }
  }

  def connect(environment: ClusterEnvironment): Future[AsyncCluster] = {
    Future {
      Cluster.connect(environment).async
    }
  }
}