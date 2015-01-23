package controllers

import models._
import play.api.Play
import play.api.data.validation.ValidationError
import play.api.libs.Crypto
import play.api.libs.json._
import play.api.mvc.Results.EmptyContent

import play.api.mvc._
import scalikejdbc.async.{AsyncDBSession, AsyncDB}
import utils.{GithubUtil, AuthInfo, UnauthorizedError, ForceUtil}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


object Application extends Controller {

  lazy val forceUtil = ForceUtil(Play.current)
  lazy val githubUtil = GithubUtil(Play.current)

  val X_ENCRYPTED_OWNER_ID = "X-ENCRYPTED-OWNER-ID"
  val X_GITHUB_ACCESS_TOKEN = "X-GITHUB-ACCESS-TOKEN"

  private def errorsToJson(errors: Seq[(JsPath, Seq[ValidationError])]): JsObject = {
    Json.obj("errors" -> errors.toString())
  }

  class RequestWithOwnerId[A](val ownerId: String, request: Request[A]) extends WrappedRequest[A](request)

  object OwnerAction extends ActionBuilder[RequestWithOwnerId] with ActionRefiner[Request, RequestWithOwnerId] {
    override def refine[A](request: Request[A]): Future[Either[Result, RequestWithOwnerId[A]]] = {
      Future.successful {
        request.headers.get(X_ENCRYPTED_OWNER_ID).orElse(request.flash.get(X_ENCRYPTED_OWNER_ID)).map { encryptedOwnerId =>
          new RequestWithOwnerId(Crypto.decryptAES(encryptedOwnerId), request)
        } toRight {
          render {
            case Accepts.Html() => Redirect(routes.Application.login())
            case Accepts.Json() => Unauthorized(Json.obj("error" -> s"The $X_ENCRYPTED_OWNER_ID header was not set"))
          } (request)
        }
      }
    }
  }

  private def refreshHandler[A](org: Org)(f: (Org => Future[A])): Future[A] = {
    f(org).recoverWith {
      case e: UnauthorizedError =>
        // fetch a new access token using the refresh token
        forceUtil.refreshToken(org).flatMap { accessToken =>
          // update the org with the new access token
          org.updateAccessToken(accessToken).flatMap { updatedOrg =>
            // try the call again
            f(updatedOrg)
          }
        }
    }
  }

  def app = OwnerAction { implicit request =>
    val encOwnerId = Crypto.encryptAES(request.ownerId)
    val githubAuthUrl = githubUtil.authUrl(encOwnerId)
    Ok(views.html.app(encOwnerId, githubAuthUrl))
  }

  def login = Action {
    Ok(views.html.login())
  }

  def orgs = OwnerAction.async { request =>
    Org.findAllByOwnerId(request.ownerId).map { orgs =>
      Ok(Json.toJson(orgs))
    } recover { case e: Error =>
      NotFound("")
    }
  }

  def metadata(orgId: Long) = OwnerAction.async { request =>
    for {
      org <- Org.find(orgId, request.ownerId)
      apexClassesJson <- refreshHandler[JsValue](org)(forceUtil.apexClasses)
    } yield {
      val orgJson = Json.toJson(org).as[JsObject]
      Ok(orgJson + ("apexclasses" -> apexClassesJson))
    }
  }

  def metadataUpdate(orgId: Long) = OwnerAction.async(parse.json) { request =>

    case class ApexUpdates(creates: Map[String, String], updates: Map[String, String], deletes: Seq[String])

    val maybeApexUpdates = for {
      creates <- (request.body \ "apexClasses" \ "creates").asOpt[Map[String, String]]
      updates <- (request.body \ "apexClasses" \ "updates").asOpt[Map[String, String]]
      deletes <- (request.body \ "apexClasses" \ "deletes").asOpt[Seq[String]]
    } yield ApexUpdates(creates, updates, deletes)

    maybeApexUpdates.fold(Future.successful(BadRequest(""))) { apexUpdates =>
      // todo: refresh handler

      val orgFuture = Org.find(orgId, request.ownerId)

      val apexUpdatesFuture = orgFuture.flatMap { org =>
        val createsFutures = apexUpdates.creates.map { case (name, body) =>
          forceUtil.createApexClass(name, body, org)
        }
        val updatesFutures = forceUtil.updateApexClasses(apexUpdates.updates, org)
        val deletesFutures = apexUpdates.deletes.map { id =>
          forceUtil.deleteApexClass(id, org)
        }

        Future.sequence(createsFutures ++ Seq(updatesFutures) ++ deletesFutures)
      }

      apexUpdatesFuture.map(_ => Ok(EmptyContent())).recover { case e: Exception =>
        InternalServerError(e.toString)
      }
    }

  }

  def oauthLoginProd() = Action { implicit request =>
    Redirect(forceUtil.loginUrl(forceUtil.ENV_PROD)).flashing(forceUtil.SALESFORCE_ENV -> forceUtil.ENV_PROD)
  }

  def oauthLoginSandbox() = Action { implicit request =>
    Redirect(forceUtil.loginUrl(forceUtil.ENV_SANDBOX)).flashing(forceUtil.SALESFORCE_ENV -> forceUtil.ENV_SANDBOX)
  }

  def oauthAddProdOrg(encOwnerId: String) = Action { implicit request =>
    val addOrgUrl = forceUtil.loginUrl(forceUtil.ENV_PROD) + s"&prompt=login&state=$encOwnerId"
    Redirect(addOrgUrl).flashing(forceUtil.SALESFORCE_ENV -> forceUtil.ENV_PROD)
  }

  def oauthAddSandboxOrg(encOwnerId: String) = Action { implicit request =>
    val addOrgUrl = forceUtil.loginUrl(forceUtil.ENV_SANDBOX) + s"&prompt=login&state=$encOwnerId"
    Redirect(addOrgUrl).flashing(forceUtil.SALESFORCE_ENV -> forceUtil.ENV_SANDBOX)
  }

  // the ownerId is encrypted so that it can't be overwritten
  def oauthCallback(code: String, maybeEncOwnerId: Option[String]) = Action.async { implicit request =>

    val env = request.flash.get(forceUtil.SALESFORCE_ENV).getOrElse(forceUtil.ENV_PROD)

    def createOrg(authInfo: AuthInfo, orgId: String, username: String, userId: String, ownerId: String, salesforceUserFuture: Future[SalesforceUser])(implicit session: AsyncDBSession): Future[SalesforceUser] = {
      forceUtil.orgInfo(authInfo, orgId).flatMap { orgInfo =>
        val name = (orgInfo \ "Name").as[String]
        val edition = (orgInfo \ "OrganizationType").as[String]

        // add the org
        Org.create(orgId, name, username, edition, authInfo.accessToken, authInfo.refreshToken, env, authInfo.instanceUrl, ownerId).flatMap { _ =>
          // if the SalesforceUser can't be found then create one
          salesforceUserFuture.recoverWith {
            case e: SalesforceUserNotFound =>
              SalesforceUser.create(userId, ownerId)
          }
        }
      }
    }

    val loginFuture = forceUtil.login(code, env)

    loginFuture.flatMap { authInfo =>
      forceUtil.userInfo(authInfo).flatMap { userInfo =>
        val userId = (userInfo \ "user_id").as[String]
        val username = (userInfo \ "username").as[String]
        val orgId = (userInfo \ "organization_id").as[String]

        val salesforceUserFuture = SalesforceUser.find(userId)

        maybeEncOwnerId.fold {
          // login

          salesforceUserFuture.flatMap { salesforceUser =>
            // update the orgs which are associated with the user that logged in (otherwise old refresh tokens expire)
            Org.updateTokens(salesforceUser.ownerId, orgId, authInfo).map { _ =>
              salesforceUser
            }
          } recoverWith {
            // if the SalesforceUser can't be found then create one
            case e: SalesforceUserNotFound =>
              AsyncDB.localTx { implicit tx =>
                for {
                  owner <- Owner.create
                  salesforceUser <- createOrg(authInfo, orgId, username, userId, owner.id, salesforceUserFuture)
                } yield salesforceUser
              }
          }
        } { encOwnerId =>
          // add org
          val ownerId = Crypto.decryptAES(encOwnerId)
          createOrg(authInfo, orgId, username, userId, ownerId, salesforceUserFuture)(AsyncDB.sharedSession)
        }
      }
    } map { salesforceUser =>
      val encOwnerId = Crypto.encryptAES(salesforceUser.ownerId)
      Redirect(routes.Application.app()).flashing(X_ENCRYPTED_OWNER_ID -> encOwnerId)
    } recover { case e: Error =>
      Redirect(routes.Application.login())
    }
  }

  def githubStatus = OwnerAction.async { request =>
    Owner.find(request.ownerId).map { owner =>
      Ok(Json.obj("isReady" -> owner.githubAccessToken.isDefined))
    }
  }

  def githubRepos = OwnerAction.async { request =>
    Owner.find(request.ownerId).flatMap { owner =>
      owner.githubAccessToken.fold(Future.successful(Unauthorized("No GitHub access token found"))) { accessToken =>
        githubUtil.repos(accessToken).map { repos =>
          import play.api.libs.json._
          val justFullName = (__ \ 'full_name).json.pickBranch
          Ok(JsArray(repos.value.map(_.transform(justFullName).get)))
        }
      }
    }
  }

  def githubOauthCallback(code: String, encOwnerId: String) = Action.async { implicit request =>
    val ownerId = Crypto.decryptAES(encOwnerId)
    githubUtil.accessToken(code).flatMap { accessToken =>
      Owner.find(ownerId).flatMap { owner =>
        owner.updateGithubAccessToken(accessToken).map { _ =>
          Redirect(routes.Application.app()).flashing(X_ENCRYPTED_OWNER_ID -> encOwnerId)
        }
      }
    }
  }

}
