package object utils {

  case class UnauthorizedError(message: String) extends Exception {
    override def getMessage: String = message
  }

  case class RequestError(message: String) extends Exception {
    override def getMessage: String = message
  }

}
