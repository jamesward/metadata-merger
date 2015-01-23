package utils

import play.api.libs.json.{JsString, JsArray, Json}
import play.api.libs.ws.{WSAuthScheme, WS}
import play.api.test.Helpers._
import org.scalatestplus.play.{OneAppPerSuite, PlaySpec}
import scala.concurrent.ExecutionContext.Implicits.global


class GithubUtilSpec extends PlaySpec with OneAppPerSuite {

  val githubUtil = GithubUtil(app)

  val clientId = app.configuration.getString("github.oauth.client-id").get
  val clientSecret = app.configuration.getString("github.oauth.client-secret").get

  val githubUsername = sys.env("GITHUB_USERNAME")
  val githubPassword = sys.env("GITHUB_PASSWORD")

  val accessToken = await {
    WS.url("https://api.github.com/authorizations")
      .withAuth(githubUsername, githubPassword, WSAuthScheme.BASIC)
      .post {
        Json.obj(
          "scopes" -> JsArray(Seq(JsString("public_repo"))),
          "client_id" -> JsString(clientId),
          "client_secret" -> JsString(clientSecret)
        )
      } map { response =>
        (response.json \ "token").as[String]
      }
  }

  "GithubUtil" must {
    "fetch all the repos with 5 pages" in {
      val repos = await(githubUtil.allRepos("user/repos", accessToken, 1))
      repos.value.length must equal (5)
    }
    "fetch all the repos without paging" in {
      val repos = await(githubUtil.allRepos("user/repos", accessToken, 5))
      repos.value.length must equal (5)
    }
    "fetch all the repos with 2 pages" in {
      val repos = await(githubUtil.allRepos("user/repos", accessToken, 4))
      repos.value.length must equal (5)
    }
  }

}