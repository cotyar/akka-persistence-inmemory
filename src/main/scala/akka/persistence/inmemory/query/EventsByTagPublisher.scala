/*
 * Copyright 2016 Dennis Vriend
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.persistence.inmemory
package query

import akka.actor.ActorLogging
import akka.persistence.inmemory.extension.{ InMemoryJournalStorage, StorageExtension }
import akka.persistence.query.EventEnvelope
import akka.persistence.query.journal.leveldb.DeliveryBuffer
import akka.stream.Materializer
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{ Cancel, Request }
import akka.stream.scaladsl.{ Sink, Source }
import akka.pattern.ask
import akka.persistence.PersistentRepr
import akka.serialization.SerializationExtension
import akka.util.Timeout

import scala.concurrent.duration.{ FiniteDuration, _ }
import scala.concurrent.{ ExecutionContext, Future }

object EventsByTagPublisher {
  sealed trait Command
  case object GetEventsByTag extends Command
  case object BecomePolling extends Command
  case object DetermineSchedulePoll extends Command
}

class EventsByTagPublisher(tag: String, offset: Int, refreshInterval: FiniteDuration, maxBufferSize: Int)(implicit ec: ExecutionContext, mat: Materializer, timeout: Timeout) extends ActorPublisher[EventEnvelope] with DeliveryBuffer[EventEnvelope] with ActorLogging {
  import EventsByTagPublisher._
  val journal = StorageExtension(context.system).journalStorage
  val serialization = SerializationExtension(context.system)

  def determineSchedulePoll(): Unit = {
    if (buf.size < maxBufferSize && totalDemand > 0)
      context.system.scheduler.scheduleOnce(0.seconds, self, BecomePolling)
  }

  val checkPoller = context.system.scheduler.schedule(0.seconds, refreshInterval, self, DetermineSchedulePoll)

  def receive = active(offset)

  def polling(offset: Long): Receive = {
    case GetEventsByTag ⇒
      log.debug("[EventsByTagPublisher]: GetEventByTag, from offset: {}", offset)
      Source.fromFuture((journal ? InMemoryJournalStorage.EventsByTag(tag, offset)).mapTo[List[JournalEntry]])
        .mapConcat(identity)
        .take(Math.max(0, maxBufferSize - buf.size))
        .map(entry ⇒ (entry.ordering, entry.serialized))
        .mapAsync(1)(arr ⇒ Future.fromTry(serialization.deserialize(arr._2, classOf[PersistentRepr])).map((arr._1, _)))
        .map { case (ordering, repr) ⇒ EventEnvelope(ordering, repr.persistenceId, repr.sequenceNr, repr.payload) }
        .runWith(Sink.seq)
        .map { xs ⇒
          buf = buf ++ xs
          log.debug("[EventsByTagPublisher]: Total buffer: {} and deliver", buf)
          val latestOrdering = if (buf.nonEmpty) buf.map(_.offset).max + 1 else offset
          deliverBuf()
          log.debug("[EventsByTagPublisher]: After deliver call: {}, storing offset (plus 1): {}", buf, latestOrdering)
          context.become(active(latestOrdering))
        }.recover {
          case t: Throwable ⇒
            onError(t)
            context.stop(self)
        }

    case Cancel ⇒
      log.debug("Upstream cancelled the stream, stopping self: {}", self.path)
      context.stop(self)
  }

  def active(offset: Long): Receive = {
    case BecomePolling ⇒
      context.become(polling(offset))
      self ! GetEventsByTag

    case DetermineSchedulePoll if (buf.size - totalDemand) < 0 ⇒ determineSchedulePoll()

    case DetermineSchedulePoll                                 ⇒ deliverBuf()

    case Request(req)                                          ⇒ deliverBuf()

    case Cancel ⇒
      log.debug("Upstream cancelled the stream, stopping self: {}", self.path)
      context.stop(self)
  }

  override def postStop(): Unit = {
    checkPoller.cancel()
    super.postStop()
  }
}