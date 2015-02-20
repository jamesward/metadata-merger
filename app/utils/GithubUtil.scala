package utils

import java.net.URL
import java.util.concurrent.TimeUnit

import models.Org
import org.apache.commons.codec.binary.Base64
import play.api.Application
import play.api.http.{MimeTypes, HeaderNames, Status}
import play.api.libs.concurrent.Akka
import play.api.libs.json.{JsObject, JsArray, JsValue, Json}
import play.api.libs.ws.{WS, WSRequestHolder, WSResponse}
import play.api.mvc.{Result, RequestHeader}
import play.api.mvc.Results.EmptyContent

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{Future, Promise}
import scala.language.implicitConversions
import scala.util.Random

class GithubUtil(implicit app: Application) {

  val clientId = app.configuration.getString("github.oauth.client-id").get
  val clientSecret = app.configuration.getString("github.oauth.client-secret").get

  def authUrl(encOwnerId: String)(implicit request: RequestHeader): String = {
    val scope = "repo"
    s"https://github.com/login/oauth/authorize?client_id=$clientId&redirect_uri=$redirectUri&scope=$scope&state=$encOwnerId"
  }

  def redirectUri(implicit request: RequestHeader): String = {
    controllers.routes.Application.githubOauthCallback("", "").absoluteURL(request.secure).stripSuffix("?code=&state=")
  }

  def ws(path: String, accessToken: String): WSRequestHolder = {
    WS
      .url(s"https://api.github.com/$path")
      .withHeaders(
        HeaderNames.AUTHORIZATION -> s"token $accessToken",
        HeaderNames.ACCEPT -> "application/vnd.github.v3+json"
      )
  }

  def accessToken(code: String)(implicit request: RequestHeader): Future[String] = {
    val wsFuture = WS.url("https://github.com/login/oauth/access_token").withQueryString(
      "client_id" -> clientId,
      "client_secret" -> clientSecret,
      "code" -> code
    ).withHeaders(HeaderNames.ACCEPT -> MimeTypes.JSON).post(EmptyContent())

    wsFuture.flatMap { response =>
      (response.json \ "access_token").asOpt[String].fold {
        Future.failed[String](UnauthorizedError(response.body))
      } {
        Future.successful
      }
    }
  }

  // deals with pagination
  def allRepos(path: String, accessToken: String, pageSize: Int = 100): Future[JsArray] = {

    import org.jboss.netty.handler.codec.http.QueryStringDecoder
    import collection.JavaConverters._

    implicit class Regex(sc: StringContext) {
      def r = new util.matching.Regex(sc.parts.mkString, sc.parts.tail.map(_ => "x"): _*)
    }

    def req(path: String, accessToken: String, page: Int, pageSize: Int): Future[WSResponse] = {
      ws(path, accessToken).withQueryString("page" -> page.toString, "per_page" -> pageSize.toString).get()
    }

    // get the first page
    req(path, accessToken, 1, pageSize).flatMap { response =>

      val firstPageRepos = response.json.as[JsArray]

      def urlToPage(urlString: String): Int = {
        val url = new URL(urlString)
        val params = new QueryStringDecoder(url.toURI.getRawQuery, false).getParameters.asScala.mapValues(_.asScala.toSeq).toMap
        params("page").head.toInt
      }

      val pages = response.header("Link") match {
        case Some(r"""<(.*)$n>; rel="next", <(.*)$l>; rel="last"""") =>
          Range(urlToPage(n), urlToPage(l) + 1)
        case _ =>
          Range(0, 0)
      }

      val pagesFutures = pages.map(req(path, accessToken, _, pageSize).map(_.json.as[JsArray]))

      // assume numeric paging so we can parallelize
      Future.fold(pagesFutures)(firstPageRepos)(_ ++ _)
    }
  }

  def repos(accessToken: String): Future[JsArray] = {
    ws("user/orgs", accessToken).get().flatMap { orgsResponse =>
      val orgNames = (orgsResponse.json \\ "login").map(_.as[String])
      val orgReposFutures = orgNames.map { orgName =>
        allRepos(s"orgs/$orgName/repos", accessToken)
      }
      val userReposFuture = allRepos("user/repos", accessToken)
      Future.fold(orgReposFutures :+ userReposFuture)(JsArray()) { case (allRepos, orgRepos) =>
        allRepos ++ orgRepos.as[JsArray]
      }
    }
  }

  def apexClasses(fullName: String, accessToken: String): Future[JsArray] = {
    ws(s"repos/$fullName/contents/classes", accessToken).get().flatMap { classesResponse =>
      classesResponse.status match {
        case Status.NOT_FOUND =>
          Future.successful(Json.arr())
        case Status.OK =>

          val classDetailFutures = classesResponse.json.as[Seq[JsObject]].map { json =>
            val path = (json \ "path").as[String]
            contents(fullName, path, accessToken)
          }

          Future.fold(classDetailFutures)(Json.arr()) { case (jsonArray, classDetails) =>
            val path = (classDetails \ "path").as[String]
            val name = (classDetails \ "name").as[String].stripSuffix(".cls")
            val base64Contents = (classDetails \ "content").as[String]

            jsonArray :+ Json.obj(
              "Id" -> name,
              "Name" -> name,
              "Body" -> new String(Base64.decodeBase64(base64Contents))
            )
          }
        case _ =>
          Future.failed(RequestError(classesResponse.body))
      }
    }
  }

  def apexClass(fullName: String, name: String, accessToken: String): Future[JsValue] = {
    ws(s"repos/$fullName/contents/${apexClassPath(name)}", accessToken).get().flatMap { response =>
      response.status match {
        case Status.OK =>
          Future.successful(response.json)
        case _ =>
          Future.failed(RequestError(response.body))
      }
    }
  }

  def contents(fullName: String, path: String, accessToken: String): Future[JsValue] = {
    ws(s"repos/$fullName/contents/$path", accessToken).get().flatMap { response =>
      response.status match {
        case Status.OK =>
          Future.successful(response.json)
        case _ =>
          Future.failed(RequestError(response.body))
      }
    }
  }

  private def apexClassPath(name: String): String = s"classes/$name.cls"

  def createApexClass(fullName: String, name: String, body: String, accessToken: String): Future[JsValue] = {
    val json = Json.obj(
      "path" -> apexClassPath(name),
      "message" -> s"Created Apex Class: $name",
      "content" -> Base64.encodeBase64String(body.getBytes)
    )
    ws(s"repos/$fullName/contents/${apexClassPath(name)}", accessToken).put(json).flatMap { response =>
      response.status match {
        case Status.CREATED =>
          Future.successful(response.json)
        case _ =>
          Future.failed(RequestError(response.body))
      }
    }
  }

  // we have to do these sequentially because:
  // https://stackoverflow.com/questions/19576601/github-api-issue-with-file-upload

  def createApexClasses(fullName: String, classes: Map[String, String], accessToken: String): Future[Iterable[JsValue]] = {
    seqFutures(classes) { case (name, body) =>
      createApexClass(fullName, name, body, accessToken)
    }
  }

  def updateApexClasses(fullName: String, classes: Map[String, String], accessToken: String): Future[Iterable[JsValue]] = {
    seqFutures(classes) { case (name, body) =>
      updateApexClass(fullName, name, body, accessToken)
    }
  }

  def deleteApexClasses(fullName: String, classes: Seq[String], accessToken: String): Future[Iterable[JsValue]] = {
    seqFutures(classes) { name =>
      deleteApexClass(fullName, name, accessToken)
    }
  }

  def updateApexClass(fullName: String, name: String, body: String, accessToken: String): Future[JsValue] = {
    apexClass(fullName, name, accessToken).flatMap { apexClassJson =>
      val sha = (apexClassJson \ "sha").as[String]

      val json = Json.obj(
        "path" -> apexClassPath(name),
        "message" -> s"Updated Apex Class: $name",
        "content" -> Base64.encodeBase64String(body.getBytes),
        "sha" -> sha
      )

      ws(s"repos/$fullName/contents/${apexClassPath(name)}", accessToken).put(json).flatMap { response =>
        response.status match {
          case Status.OK =>
            Future.successful(response.json)
          case _ =>
            Future.failed(RequestError(response.body))
        }
      }
    }
  }

  def deleteApexClass(fullName: String, name: String, accessToken: String): Future[JsValue] = {
    apexClass(fullName, name, accessToken).flatMap { apexClassJson =>
      val sha = (apexClassJson \ "sha").as[String]

      val json = Json.obj(
        "path" -> apexClassPath(name),
        "message" -> s"Deleted Apex Class: $name",
        "sha" -> sha
      )

      ws(s"repos/$fullName/contents/${apexClassPath(name)}", accessToken).withBody(json).delete().flatMap { response =>
        response.status match {
          case Status.OK =>
            Future.successful(response.json)
          case _ =>
            Future.failed(RequestError(response.body))
        }
      }
    }
  }

  def seqFutures[T, U](items: TraversableOnce[T])(f: T => Future[U]): Future[List[U]] = {
    items.foldLeft(Future.successful[List[U]](Nil)) {
      (futures, item) => futures.flatMap { values =>
        f(item).map(_ :: values)
      }
    } map (_.reverse)
  }

}

object GithubUtil {
  def apply(implicit app: Application) = new GithubUtil()
}