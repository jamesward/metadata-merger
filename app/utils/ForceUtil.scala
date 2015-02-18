package utils

import java.util.concurrent.TimeUnit

import models.Org
import play.api.Application
import play.api.libs.concurrent.Akka
import play.api.libs.json.{JsObject, Json, JsValue}
import play.api.libs.ws.{WSResponse, WSRequestHolder, WS}
import play.api.http.{Status, HeaderNames}
import play.api.mvc.RequestHeader
import play.api.mvc.Results.EmptyContent
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Promise, Future}
import scala.concurrent.duration.{Duration, FiniteDuration}

import scala.language.implicitConversions
import scala.util.Random

class ForceUtil(implicit app: Application) {

  implicit def toRichFutureWSResponse(f: Future[WSResponse]): RichFutureWSResponse = new RichFutureWSResponse(f)

  val API_VERSION = "32.0"

  val defaultTimeout = FiniteDuration(60, TimeUnit.SECONDS)
  val defaultPollInterval = FiniteDuration(1, TimeUnit.SECONDS)

  val consumerKey = app.configuration.getString("force.oauth.consumer-key").get
  val consumerSecret = app.configuration.getString("force.oauth.consumer-secret").get

  val ENV_PROD = "prod"
  val ENV_SANDBOX = "sandbox"
  val SALESFORCE_ENV = "salesforce-env"

  def loginUrl(env: String)(implicit request: RequestHeader): String = env match {
    case ENV_PROD => "https://login.salesforce.com/services/oauth2/authorize?response_type=code&client_id=%s&redirect_uri=%s".format(consumerKey, redirectUri)
    case ENV_SANDBOX => "https://test.salesforce.com/services/oauth2/authorize?response_type=code&client_id=%s&redirect_uri=%s".format(consumerKey, redirectUri)
  }

  def tokenUrl(env: String): String = env match {
    case ENV_PROD => "https://login.salesforce.com/services/oauth2/token"
    case ENV_SANDBOX => "https://test.salesforce.com/services/oauth2/token"
  }

  def userinfoUrl(env: String): String = env match {
    case ENV_PROD => "https://login.salesforce.com/services/oauth2/userinfo"
    case ENV_SANDBOX => "https://test.salesforce.com/services/oauth2/userinfo"
  }

  def redirectUri(implicit request: RequestHeader): String = {
    controllers.routes.Application.oauthCallback("", None).absoluteURL(request.secure).stripSuffix("?code=")
  }

  def ws(path: String, org: Org): WSRequestHolder = {
    WS.
      url(s"${org.instanceUrl}/services/data/v$API_VERSION/$path").
      withHeaders(HeaderNames.AUTHORIZATION -> s"Bearer ${org.accessToken}")
  }

  def login(code: String, env: String)(implicit request: RequestHeader): Future[AuthInfo] = {
    val wsFuture = WS.url(tokenUrl(env)).withQueryString(
      "grant_type" -> "authorization_code",
      "client_id" -> consumerKey,
      "client_secret" -> consumerSecret,
      "redirect_uri" -> redirectUri,
      "code" -> code
    ).post(EmptyContent())

    wsFuture.flatMap { response =>
      val maybeAuthInfo = for {
        idUrl <- (response.json \ "id").asOpt[String]
        accessToken <- (response.json \ "access_token").asOpt[String]
        refreshToken <- (response.json \ "refresh_token").asOpt[String]
        instanceUrl <- (response.json \ "instance_url").asOpt[String]
      } yield AuthInfo(idUrl, accessToken, refreshToken, instanceUrl)

      maybeAuthInfo.fold {
        Future.failed[AuthInfo](UnauthorizedError(response.body))
      } {
        Future.successful
      }
    }
  }

  def refreshToken(org: Org): Future[String] = {
    val wsFuture = WS.url(tokenUrl(org.env)).withQueryString(
      "grant_type" -> "refresh_token",
      "refresh_token" -> org.refreshToken,
      "client_id" -> consumerKey,
      "client_secret" -> consumerSecret
    ).post(EmptyContent())

    wsFuture.flatMap { response =>
      (response.json \ "access_token").asOpt[String].fold {
        Future.failed[String](UnauthorizedError(response.body))
      } {
        Future.successful
      }
    }
  }

  def orgInfo(authInfo: AuthInfo, orgId: String): Future[JsValue] = {
    val url = authInfo.instanceUrl + s"/services/data/v$API_VERSION/sobjects/Organization/$orgId"
    WS
      .url(url)
      .withHeaders(HeaderNames.AUTHORIZATION -> s"Bearer ${authInfo.accessToken}")
      .get()
      .map(_.json)
  }

  def userInfo(authInfo: AuthInfo): Future[JsValue] = {
    WS
      .url(authInfo.idUrl)
      .withHeaders(HeaderNames.AUTHORIZATION -> s"Bearer ${authInfo.accessToken}")
      .get()
      .map(_.json)
  }

  def apexClasses(org: Org): Future[JsValue] = {
    toolingQuery("select Id from ApexClass where NamespacePrefix = null", org).flatMap { apexClassesResponse =>
      val ids = (apexClassesResponse \\ "Id").map(_.as[String])
      val reqs = ids.map(apexClass(_, org))
      Future.sequence(reqs).map(Json.toJson(_))
    }
  }

  def apexClass(id: String, org: Org): Future[JsValue] = {
    ws(s"tooling/sobjects/ApexClass/$id", org).get().ok(_.json)
  }

  def createApexClass(name: String, body: String, org: Org): Future[JsValue] = {
    val json = Json.obj("Name" -> name, "Body" -> body)
    ws("tooling/sobjects/ApexClass", org).post(json).created(_.json)
  }

  def updateApexClasses(apexClasses: Map[String, String], org: Org): Future[JsValue] = {
    val randomMetadataContainerName = Seq.fill(8)((Random.nextInt(122 - 97) + 97).toChar).mkString

    // todo: cleanup from failures
    for {
      created <- createMetadataContainer(randomMetadataContainerName, org)
      metadataContainerId = (created \ "id").as[String]
      addFutures = addContentsToMetadataContainer(metadataContainerId, apexClasses, org)
      adds <- Future.sequence(addFutures)
      deploy <- deployContentInMetadataContainer(metadataContainerId, org)
      delete <- deleteMetadataContainer(metadataContainerId, org)
    } yield deploy

  }

  def deleteApexClass(id: String, org: Org): Future[Unit] = {
    ws(s"tooling/sobjects/ApexClass/$id", org).delete().noContent(_ => Unit)
  }

  def createMetadataContainer(name: String, org: Org): Future[JsValue] = {
    val json = Json.obj("Name" -> name)
    ws("tooling/sobjects/MetadataContainer", org).post(json).created(_.json)
  }

  def addContentsToMetadataContainer(metadataContainerId: String, contents: Map[String, String], org: Org): Seq[Future[JsValue]] = {
    contents.map { case (id, body) =>
      addContentToMetadataContainer(metadataContainerId, id, body, org)
    }.toSeq
  }

  def addContentToMetadataContainer(metadataContainerId: String, contentEntityId: String, body: String, org: Org): Future[JsValue] = {
    val json = Json.obj(
      "MetadataContainerId" -> metadataContainerId,
      "ContentEntityId" -> contentEntityId,
      "Body" -> body
    )
    ws("tooling/sobjects/ApexClassMember", org).post(json).created(_.json)
  }

  def deployContentInMetadataContainer(metadataContainerId: String, org: Org): Future[JsValue] = {
    val json = Json.obj(
      "MetadataContainerId" -> metadataContainerId,
      "isCheckOnly" -> false
    )
    ws("tooling/sobjects/ContainerAsyncRequest", org).post(json).flatMap { response =>
      val containerAsyncRequestId = (response.json \ "id").as[String]
      TimeoutFuture(defaultTimeout) {
        val promise = Promise[JsValue]()
        val polling = Akka.system.scheduler.schedule(Duration.Zero, defaultPollInterval) {
          ws(s"tooling/sobjects/ContainerAsyncRequest/$containerAsyncRequestId", org).get().map { response =>
            response.status match {
              case Status.OK if (response.json \ "State").as[String] == "Completed" =>
                promise.trySuccess(response.json)
              case Status.OK if (response.json \ "State").as[String] == "Queued" =>
                Unit
              case Status.OK =>
                promise.tryFailure(new Exception(response.body))
              case _ =>
                promise.tryFailure(new Exception(response.body))
            }
          }
        }
        promise.future.onComplete(result => polling.cancel())
        promise.future
      }
    }
  }

  def deleteMetadataContainer(metadataContainerId: String, org: Org): Future[Unit] = {
    ws(s"tooling/sobjects/MetadataContainer/$metadataContainerId", org).delete().noContent(_ => Unit)
  }

  def toolingQuery(q: String, org: Org): Future[JsValue] = {
    ws("tooling/query/", org).withQueryString("q" -> q).get().ok(_.json)
  }


}

object ForceUtil {
  def apply(implicit app: Application) = new ForceUtil()
}

case class AuthInfo(idUrl: String, accessToken: String, refreshToken: String, instanceUrl: String)


class RichFutureWSResponse(val future: Future[WSResponse]) extends AnyVal {

  def ok[A](f: (WSResponse => A)): Future[A] = status(f)(Status.OK)
  def okF[A](f: (WSResponse => Future[A])): Future[A] = statusF(f)(Status.OK)

  def created[A](f: (WSResponse => A)): Future[A] = status(f)(Status.CREATED)
  def createdF[A](f: (WSResponse => Future[A])): Future[A] = statusF(f)(Status.CREATED)

  def noContent[A](f: (WSResponse => A)): Future[A] = status(f)(Status.NO_CONTENT)
  def noContentF[A](f: (WSResponse => Future[A])): Future[A] = statusF(f)(Status.NO_CONTENT)

  def status[A](f: (WSResponse => A))(statusCode: Int): Future[A] = {
    future.flatMap { response =>
      statusF { response =>
        Future.successful(f(response))
      } (statusCode)
    }
  }

  def statusF[A](f: (WSResponse => Future[A]))(statusCode: Int): Future[A] = {
    future.flatMap { response =>
      response.status match {
        case `statusCode` =>
          f(response)
        case Status.UNAUTHORIZED =>
          Future.failed(UnauthorizedError((response.json \\ "message").map(_.as[String]).mkString(" ")))
        case _ =>
          Future.failed(RequestError(response.body))
      }
    }
  }

}

// From: http://stackoverflow.com/questions/16304471/scala-futures-built-in-timeout
object TimeoutFuture {
  def apply[A](timeout: FiniteDuration)(future: Future[A])(implicit app: Application): Future[A] = {

    val promise = Promise[A]()

    Akka.system.scheduler.scheduleOnce(timeout) {
      promise.tryFailure(new java.util.concurrent.TimeoutException)
    }

    promise.completeWith(future)

    promise.future
  }
}