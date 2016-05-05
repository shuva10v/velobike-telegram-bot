import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.{ActorMaterializer, Materializer}
import com.typesafe.config.{Config, ConfigFactory}
import spray.json.DefaultJsonProtocol

import scala.concurrent.ExecutionContextExecutor

case class Location(latitude: Double, longitude: Double)

case class User(id: Int, first_name: String, last_name: String, username: String)

case class Chat(id: Int)

case class Message(message_id: Int, chat: Chat, text: String, from: User, location: Option[Location])

case class Update(update_id: Int, message: Message)

case class SendVenue(chat_id: Int, latitude: Double, longitude: Double, title: String, address: String)

trait Protocols extends DefaultJsonProtocol with SprayJsonSupport {
  implicit val userFormat = jsonFormat4(User.apply)
  implicit val locationFormat = jsonFormat2(Location.apply)
  implicit val chatFormat = jsonFormat1(Chat.apply)
  implicit val messageFormat = jsonFormat5(Message.apply)
  implicit val updateFormat = jsonFormat2(Update.apply)
  implicit val sendVenueFormat = jsonFormat5(SendVenue.apply)
}


trait Service extends Protocols {
  implicit val system: ActorSystem
  implicit def executor: ExecutionContextExecutor
  implicit val materializer: Materializer

  lazy val telegramConnectionFlow: Flow[HttpRequest, HttpResponse, Any] =
    Http().outgoingConnection(config.getString("telegram.api.host"), config.getInt("telegram.api.port"))

  private def sendMessage(botToken: String, venue: SendVenue): Unit = {
    Source.single(RequestBuilding.Post(s"/bot$botToken/sendMessage", venue))
      .via(telegramConnectionFlow).runWith(Sink.head)
  }

  def config: Config
  val logger: LoggingAdapter
  val routes = {
    (pathPrefix("webhook") & post) {
      logRequest("webhook") {
        path(RestPath) { botToken =>
          logger.info(s"Webhook token ${botToken.toString()}")
          entity(as[Update]) { update =>
            update.message.location match {
              case Some(location) =>
                logger.info(s"Got request with text ${location.latitude} ${location.longitude}")
                val venue = SendVenue(update.message.chat.id, location.latitude, location.longitude, "title", "addr")
                sendMessage(botToken.toString(), venue)
                logger.info("Processing finished")
                complete(StatusCodes.OK)
              case None =>
                logger.warning("Location not found")
                complete(StatusCodes.OK)
            }
          }
        }
      }
    }
  }
}

object VelobikeBotApiService extends App with Service {
  override implicit val system = ActorSystem()
  override implicit val executor = system.dispatcher
  override implicit val materializer = ActorMaterializer()

  override val config = ConfigFactory.load()
  override val logger = Logging(system, getClass)

  Http().bindAndHandle(routes, config.getString("http.interface"), config.getInt("http.port"))
}
