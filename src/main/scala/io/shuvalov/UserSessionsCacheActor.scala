package io.shuvalov

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{Actor, ActorLogging}
import io.shuvalov.QueryType.QueryType

import scala.collection.mutable

case class UserSession(position: Position, timestamp: Long, var lastQueryType: QueryType) {
  private val offset_ = new AtomicInteger(0)
  def reset: Unit = {
    offset_.set(0)
  }

  def offset: Int = offset_.get()

  def increment(value: Int): Unit = offset_.addAndGet(value)
}

case class InitUserSession(userId: Int, position: Position)

case class GetSession(userId: Int, queryType: QueryType)

case class IncrementOffset(userId: Int, value: Int)

class UserSessionsCacheActor extends Actor with ActorLogging {
  private val cache = mutable.Map.empty[Int, UserSession]
  override def receive: Receive = {
    case InitUserSession(userId, position) =>
      cache(userId) = UserSession(position, System.currentTimeMillis(), QueryType.Bikes)
      log.info(s"Updating user location for ${userId}, total ${cache.size}")
    case GetSession(userId, queryType) =>
      sender() ! cache.get(userId).map{session =>
        // reset offset if query type is differ from last one
        if (session.lastQueryType != queryType) {
          session.reset
          session.lastQueryType = queryType
        }
        session
      }
    case IncrementOffset(userId, value) =>
      log.info(s"Increment offset: ${userId} to ${value}")
      cache.get(userId).foreach(_.increment(value))
  }
}
