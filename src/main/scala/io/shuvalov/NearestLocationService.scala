package io.shuvalov

import java.io.IOException

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import spray.json.DefaultJsonProtocol

import scala.concurrent.{ExecutionContextExecutor, Future}

sealed trait ErrorStatus

case object LongDistance extends ErrorStatus

case object ServiceError extends ErrorStatus

case object NoParkingsAvailable extends ErrorStatus

case class Position(Lat: Double, Lon: Double)
case class Parking(Id: String, IsLocked: Boolean, Address: String, FreePlaces: Int,
									 TotalPlaces: Int, Position: Position)

case class ParkingsList(Items: Seq[Parking])

trait VelobikeJsonProtocol extends DefaultJsonProtocol with SprayJsonSupport {
	implicit val positionFormat = jsonFormat2(Position)
	implicit val parkingFormat = jsonFormat6(Parking)
	implicit val parkingsListFormat = jsonFormat1(ParkingsList)
}

/**
	* Calculates nearest available parking. Implementation is not optimal, todos:
	* 1. Cache request for a reasonable time to reduce processing delay
	* 2.
	*/
trait NearestLocationService extends VelobikeJsonProtocol {
	implicit val system: ActorSystem
	implicit def executor: ExecutionContextExecutor
	implicit val materializer: Materializer
	implicit val logger: LoggingAdapter

	lazy val connectionFlow: Flow[HttpRequest, HttpResponse, Any] =
		Http().outgoingConnection("http://velobike.ru/proxy/parkings/")

	private def parkings(): Future[Seq[Parking]] = {
		Source.single(RequestBuilding.Get()).via(connectionFlow).runWith(Sink.head).flatMap {
			response =>
				response.status match {
					case StatusCodes.OK => Unmarshal(response.entity).to[ParkingsList].map(_.Items)
					case other =>  Future.failed(new IOException(s"HTTP response ${other}"))
				}
		}
	}

	private val MAX_DISTANCE_FROM_PARKING = 1.0

	def nearest(position: Position): Future[Either[ErrorStatus, Parking]] = {
		parkings().map(parkings => {
			val sorted = parkings
				.filter(_.IsLocked == false)
			  .filter(_.FreePlaces > 0)
			  .map(parking => {
					(distance(position, parking.Position), parking)
				}).sortBy(_._1)
			if (sorted.size == 0) {
				logger.error("No parkings available!")
				Left(NoParkingsAvailable)
			} else {
				val (minDistance, parking) = sorted.head
				logger.info(s"${minDistance}")
				if (minDistance > MAX_DISTANCE_FROM_PARKING) {
					Left(LongDistance)
				} else {
					Right(parking)
				}
			}
		})
	}

	def distance(a: Position, b: Position): Double = {
		val x = a.Lat - b.Lat
		val y = a.Lon - b.Lon
		x * x + y * y
	}
}
