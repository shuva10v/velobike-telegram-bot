package io.shuvalov

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.stream.{ActorMaterializer, Materializer}
import com.typesafe.config.{Config, ConfigFactory}
import spray.json.{DefaultJsonProtocol, _}

import scala.collection.mutable
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
  private val messageNoParkings = "messages.no-parkings"
  private val messageSendLocationFirst = "messages.send-location-first"
  private val messageLocationIsOutdated = "messages.location-is-outdated"

  private val commandNext = "/next"
  private val commandLocks = "/locks"
  private val commandNextGroup = "/next ([0-9])".r
  private val commandLocksGroup = "/locks ([0-9])".r

  private val locationTtl = config.getDuration("location-ttl")


  private def sendVenueToUser(botToken: String, venue: SendVenue): Unit = {
    logger.info(venue.toJson.compactPrint)
    Http().singleRequest(HttpRequest(HttpMethods.POST,
      s"https://api.telegram.org/bot$botToken/sendVenue",
      entity = HttpEntity(ContentTypes.`application/json`, venue.toJson.prettyPrint)))
  }

  private def sendReply(botToken: String, update: Update, text: String): Unit = {
    sendMessage(botToken, SendMessage(update.message.chat.id, text))
  }

  private def sendMessage(botToken: String, message: SendMessage): Future[HttpResponse] = {
    logger.info(message.toJson.compactPrint)
    Http().singleRequest(HttpRequest(HttpMethods.POST,
      s"https://api.telegram.org/bot$botToken/sendMessage",
      entity = HttpEntity(ContentTypes.`application/json`, message.toJson.prettyPrint)))
  }

  private def message(tag: String): String = {
    config.getString(tag)
  }

  def config: Config

  val logger: LoggingAdapter

  val userLocations: mutable.Map[Int, (Position, Long)] = mutable.Map.empty
  val userOffset: mutable.Map[Int, AtomicInteger] = mutable.Map.empty.withDefault(x => new AtomicInteger(0))

  private def queryParkingForUser(userId: Int, count: Int = 1, offset: Int = 0,
                                  queryType: QueryType.EnumVal = QueryType.Bikes)
                                 (implicit sendText: (String) => Unit,
                                  sendVenue: (SendVenue) => Unit, chatId: Int): Unit = {
    userLocations.get(userId) match {
      case Some((position, timestamp)) =>
        if (System.currentTimeMillis() - timestamp > locationTtl.toMillis) {
           sendText(messageLocationIsOutdated)
        } else {
          queryParking(userId, position, count, offset, queryType)
        }
      case None => sendText(messageSendLocationFirst)
    }
  }

  private def queryParking(userId: Int, position: Position, count: Int = 1, offset: Int = 0,
                           queryType: QueryType.EnumVal = QueryType.Bikes)
                          (implicit sendText: (String) => Unit, sendVenue: (SendVenue) => Unit, chatId: Int): Unit = {
    nearest(position, count + offset, queryType).onComplete {
      case Success(result) =>
        result match {
          case Left(error) =>
            error match {
              case ServiceError => sendText(messageServerError)
              case LongDistance => sendText(messageLongDistance)
              case NoParkingsAvailable => sendText(messageNoParkings)
            }
          case Right(parkings) =>
            val window = parkings.slice(offset, count + offset)
            if (window.size == 0) {
              sendText(messageNoParkings)
            } else {
              userOffset(userId).addAndGet(window.size)
              for (parking <- window) {
                val venue = SendVenue(chatId, parking.Position.Lat, parking.Position.Lon,
                  s"${parking.Id} - ${parking.TotalPlaces - parking.FreePlaces}/${parking.TotalPlaces} bikes",
                  parking.Address)
                sendVenue(venue)
              }
            }
        }
      case Failure(e) => {
        logger.error("Unable to get nearest location")
        sendText(messageServerError)
        throw e
      }
    }
  }

  val routes = {
    (pathPrefix("webhook") & post) {
      logRequest("webhook") {
        path(RestPath) { botTokenPath =>
          val token = botTokenPath.toString
          entity(as[Update]) { update =>
            implicit val sendText = (msg: String) => sendReply(token, update, message(msg))
            implicit val sendVenue = (venue: SendVenue) => sendVenueToUser(token, venue)
            implicit val chatId = update.message.chat.id
            val userId = update.message.from.id
            logger.info(update.toJson.compactPrint)

            // firstly handle location from user
            update.message.location match {
              case Some(location) =>
                val position = Position(location.latitude, location.longitude)
                //save user location for session queries
                userLocations(userId) = (position, System.currentTimeMillis)
                // reset user offset
                userOffset(userId).set(0)
                logger.info(s"Updating user location for ${update.message.from.id}, total ${userLocations.size}")
                // query nearest parking with bikes available
                queryParking(userId, position)
                complete(StatusCodes.OK)
              case None =>
                logger.warning("Location not found, processing text")
                update.message.text match {
                  case Some(text) =>
                    val offset = userOffset(userId).get()
                    text match {
                      case commandNext =>
                        queryParkingForUser(userId, queryType = QueryType.Locks, offset = offset)
                      case commandLocks =>
                        queryParkingForUser(userId, queryType = QueryType.Locks, offset = offset)
                      case commandNextGroup(n) =>
                        queryParkingForUser(userId, queryType = QueryType.Locks, count = n.toInt, offset = offset)
                      case commandLocksGroup(n) =>
                        queryParkingForUser(userId, queryType = QueryType.Locks, count = n.toInt, offset = offset)
                      case other =>
                        logger.warning(s"Unsupported command '${other}'")
                        sendText(messageUsage)
                    }
                  case None => sendText(messageUsage)
                }
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
