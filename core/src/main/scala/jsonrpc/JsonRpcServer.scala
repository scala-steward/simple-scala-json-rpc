package jsonrpc

import cats.{Functor, Monad}
import play.api.libs.json.JsValue
import play.api.libs.json._
import cats.implicits._

import scala.util.Try

trait JsonRpcServer[F[_]] {

  def handle(request: String): F[String]

}

trait Handler[F[_]] {
  def handle(a: JsValue): F[Either[JsonRpcError, JsValue]]

  def methodName: String
}


case class JsonRpcRequest(jsonrpc: String, id: String, method: String, params: JsValue)

object JsonRpcRequest {
  implicit val JsonRpcRequestFormat: OFormat[JsonRpcRequest] = Json.format[JsonRpcRequest]
}



object Handler {
  def create[A, B, F[_] : Monad](definition: MethodDefinition[A, B],
                                   method: A => F[Either[JsonRpcError, B]]): Handler[F] = new Handler[F] {

    override def handle(a: JsValue): F[Either[JsonRpcError, JsValue]] = {
      definition.req.reads(a).asEither.left.map(_ => JsonRpcError.InvalidParams).fold(
        error => Monad[F].pure(error),
        request => method(request).map(_.map(definition.res.writes))
      )
    }

    override def methodName: String = definition.methodName
  }
}


object JsonRpcServer {

  def create[F[_] : Monad](handlers: List[Handler[F]]): JsonRpcServer[F] = new JsonRpcServer[F] {
    override def handle(request: String): F[String] = {
      val parsed = Try(Json.parse(request)).toEither.left.map(_ => JsonRpcError.ParseError)
      val res: Either[JsonRpcError, F[Either[JsonRpcError, JsValue]]] = for {
        json <- parsed
        rpcRequest <- json.validate[JsonRpcRequest].asEither.left.map(_ => JsonRpcError.InvalidRequest)
        handler <- handlers.find(_.methodName == rpcRequest.method).toRight(JsonRpcError.MethodNotFound)
      } yield handler.handle(rpcRequest.params)

      val prefix = Json.obj(
        "jsonrpc" -> "2.0",
        "id" -> parsed.toOption.flatMap(r => (r \ "id").toOption).getOrElse(JsNull))


      res match {
        case Left(error) => Monad[F].pure(prefix deepMerge Json.obj("error" -> error.render))
        case Right(res) => res.map {
          case Left(error) => Json.stringify(prefix deepMerge Json.obj("error" -> error.render))
          case Right(value) => Json.stringify(prefix deepMerge Json.obj("result" -> value))
        }
      }
    }
  }
}