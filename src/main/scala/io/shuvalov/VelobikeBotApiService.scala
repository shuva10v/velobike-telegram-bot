package io.shuvalov

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.stream.{ActorMaterializer, Materializer}
import com.typesafe.config.{Config, ConfigFactory}
import spray.json.{DefaultJsonProtocol, _}

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

case class Location(latitude: Double, longitude: Double)

case class User(id: Int, first_name: String, last_name: Option[String], username: Option[String])

case class Chat(id: Int)

case class Message(message_id: Int, chat: Chat, text: Option[String], from: User, location: Option[Location])

case class Update(update_id: Int, message: Message)

case class SendVenue(chat_id: Int, latitude: Double, longitude: Double, title: String, address: String)

case class SendMessage(chat_id: Int, text: String)

trait Protocols extends DefaultJsonProtocol with SprayJsonSupport {
  implicit val userFormat = jsonFormat4(User.apply)
  implicit val locationFormat = jsonFormat2(Location.apply)
  implicit val chatFormat = jsonFormat1(Chat.apply)
  implicit val messageFormat = jsonFormat5(Message.apply)
  implicit val updateFormat = jsonFormat2(Update.apply)
  implicit val sendVenueFormat = jsonFormat5(SendVenue.apply)
  implicit val sendMessageFormat = jsonFormat2(SendMessage.apply)
}


trait Service extends Protocols with NearestLocationService {
  implicit val system: ActorSystem

  implicit def executor: ExecutionContextExecutor

  implicit val materializer: Materializer

  private val messageServerError = "messages.server-error"
  private val messageLongDistance = "messages.long-distance"
  private val messageUsage = "messages.usage"
  private val messageNoParkings = "no-parkings"


  private def sendVenue(botToken: String, venue: SendVenue): Future[HttpResponse] = {
    Http().singleRequest(HttpRequest(HttpMethods.POST,
      s"https://api.telegram.org/bot$botToken/sendVenue",
      entity = HttpEntity(ContentTypes.`application/json`, venue.toJson.prettyPrint)))
  }

  private def sendReply(botToken: String, update: Update, text: String): Unit = {
    sendMessage(botToken, SendMessage(update.message.chat.id, text))
  }

  private def sendMessage(botToken: String, message: SendMessage): Future[HttpResponse] = {
    Http().singleRequest(HttpRequest(HttpMethods.POST,
      s"https://api.telegram.org/bot$botToken/sendMessage",
      entity = HttpEntity(ContentTypes.`application/json`, message.toJson.prettyPrint)))
  }

  private def message(tag: String): String = {
    config.getString(tag)
  }

  def config: Config

  val logger: LoggingAdapter
  val routes = {
    (pathPrefix("webhook") & post) {
      logRequest("webhook") {
        path(RestPath) { botTokenPath =>
          val token = botTokenPath.toString
          entity(as[Update]) { update =>
            update.message.location match {
              case Some(location) =>
                val position = Position(location.latitude, location.longitude)
                logger.info(s"Got request with text ${position.Lat} ${position.Lon}")
                nearest(position).onComplete {
                  case Success(result) =>
                    result match {
                      case Left(error) =>
                        error match {
                          case ServiceError => sendReply(token, update, message(messageServerError))
                          case LongDistance => sendReply(token, update, message(messageLongDistance))
                          case NoParkingsAvailable => sendReply(token, update, message(messageNoParkings))
                        }
                      case Right(parking) =>
                        val venue = SendVenue(update.message.chat.id, parking.Position.Lat, parking.Position.Lon,
                          s"${parking.Id} - ${parking.FreePlaces}/${parking.TotalPlaces} bikes", parking.Address)
                        sendVenue(token, venue)
                    }
                  case Failure(e) => {
                    logger.error("Unable to get nearest location")
                    sendReply(token, update, message(messageServerError))
                    throw e
                  }
                }
                complete(StatusCodes.OK)
              case None =>
                logger.warning("Location not found")
                sendReply(token, update, message(messageUsage))
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
