import akka.event.NoLogging
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest._

class VelobikeBotApiServiceSpec extends FlatSpec with Matchers with ScalatestRouteTest with Service {
  override def testConfigSource = "akka.loglevel = WARNING"
  override def config = testConfig
  override val logger = NoLogging


//  override lazy val ipApiConnectionFlow = Flow[HttpRequest].map { request =>
//    if (request.uri.toString().endsWith(ip1Info.query))
//      HttpResponse(status = OK, entity = marshal(ip1Info))
//    else if(request.uri.toString().endsWith(ip2Info.query))
//      HttpResponse(status = OK, entity = marshal(ip2Info))
//    else
//      HttpResponse(status = BadRequest, entity = marshal("Bad ip format"))
//  }
//
//  "Service" should "respond to single IP query" in {
//    Get(s"/ip/${ip1Info.query}") ~> routes ~> check {
//      status shouldBe OK
//      contentType shouldBe `application/json`
//      responseAs[IpInfo] shouldBe ip1Info
//    }
//
//    Get(s"/ip/${ip2Info.query}") ~> routes ~> check {
//      status shouldBe OK
//      contentType shouldBe `application/json`
//      responseAs[IpInfo] shouldBe ip2Info
//    }
//  }
//
//  it should "respond to IP pair query" in {
//    Post(s"/ip", IpPairSummaryRequest(ip1Info.query, ip2Info.query)) ~> routes ~> check {
//      status shouldBe OK
//      contentType shouldBe `application/json`
//      responseAs[IpPairSummary] shouldBe ipPairSummary
//    }
//  }
//
//  it should "respond with bad request on incorrect IP format" in {
//    Get("/ip/asdfg") ~> routes ~> check {
//      status shouldBe BadRequest
//      responseAs[String].length should be > 0
//    }
//
//    Post(s"/ip", IpPairSummaryRequest(ip1Info.query, "asdfg")) ~> routes ~> check {
//      status shouldBe BadRequest
//      responseAs[String].length should be > 0
//    }
//
//    Post(s"/ip", IpPairSummaryRequest("asdfg", ip1Info.query)) ~> routes ~> check {
//      status shouldBe BadRequest
//      responseAs[String].length should be > 0
//    }
//  }
}
