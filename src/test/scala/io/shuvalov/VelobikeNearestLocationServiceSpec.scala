package io.shuvalov

import akka.event.Logging
import akka.http.scaladsl.model._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.stream.scaladsl.Flow
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.Await
import scala.concurrent.duration.Duration.Inf
import scala.io.Source


class VelobikeNearestLocationServiceSpec extends FlatSpec with Matchers with ScalatestRouteTest
	with NearestLocationService {
	override val logger = Logging(system, getClass)

	  override lazy val connectionFlow = Flow[HttpRequest].map { request =>
			HttpResponse(status = StatusCodes.OK, entity =
				HttpEntity(ContentTypes.`application/json`,
					Source.fromInputStream(getClass.getResourceAsStream("velobike.json")).mkString))
		}

	"Nearest location service" should "return some nearest location in Moscow" in {
		Await.result(nearest(Position(55.751606, 37.618538)), Inf).isRight shouldBe true
	}

	it should "return error status if outside Moscow" in {
		Await.result(nearest(Position(54.628231,39.729922)), Inf).left.get shouldBe LongDistance
	}

	it should "return parking on exact location" in {
		Await.result(nearest(Position(55.7914268, 37.5905396)), Inf).right.get.Id shouldBe "0415"
	}

	it should "ignore locked parking" in {
		// 0440 is locked
		Await.result(nearest(Position(55.7804054, 37.6334722)), Inf).right.get.Id shouldBe "0441"
	}

	it should "ignore empty parking" in {
		// 0394 has no available bikes
		Await.result(nearest(Position(55.6771438, 37.5629419)), Inf).right.get.Id shouldBe "0362"
	}

	it should "calculate distance" in {
		distance(Position(55.6771438, 37.5629419), Position(55.7804054, 37.6334722)) shouldBe 0.01563 +- 1e-4
	}
}
