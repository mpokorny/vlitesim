package edu.nrao.vlite

import scala.collection.mutable
import akka.actor._
import akka.util.Timeout

abstract class ValueSourceBase[V] extends Actor {

  val bufferSize: Int

  private val buffer: mutable.Queue[V] = mutable.Queue()

  private val pending: mutable.Queue[ActorRef] = mutable.Queue()

  def requestValue(): Unit

  def receiveValue(a: Any): List[V]

  private def sendValue(v: V, to: ActorRef) {
    to ! ValueSource.Value(v)
    if (buffer.length < bufferSize)
      requestValue()
  }

  private def sendToPending() {
    if (pending.length > 0 && buffer.length > 0) {
      sendValue(buffer.dequeue(), pending.dequeue())
      sendToPending()
    }
  }

  override def preStart() {
    requestValue()
  }

  def receive: Receive = {
    case ValueSource.Get =>
      pending.enqueue(sender)
    case ValueSource.Value(v) =>
      receiveValue(v) match {
        case Nil =>
          requestValue()
        case rcvd =>
          buffer ++= rcvd
          if (buffer.length < bufferSize)
            requestValue()
          else
            sendToPending()
            context.become(serving)
      }
  }

  def serving: Receive = {
    case ValueSource.Get =>
      if (buffer.length > 0) sendValue(buffer.dequeue(), sender)
      else pending.enqueue(sender)
    case ValueSource.Value(v) =>
      receiveValue(v) match {
        case Nil =>
          requestValue()
        case rcvd =>
          buffer ++= rcvd
          sendToPending()
      }
  }
}

class ValueSource[V](sourceProps: Seq[Props], val bufferSize: Int)
    extends ValueSourceBase[V] {

  var nextSourceIndex = 0

  val sources = sourceProps.toVector map (p => context.actorOf(p))

  def requestValue() {
    sources(nextSourceIndex) ! ValueSource.Get
    nextSourceIndex = (nextSourceIndex + 1) % sources.length
  }

  def receiveValue(a: Any): List[V] =
    List(a.asInstanceOf[V])
}

object ValueSource {
  def props[V](generate: () => V, bufferSize: Int): Props =
    props(Vector(generate), bufferSize)

  def props[V](generators: Seq[() => V], bufferSize: Int): Props =
    Props(
      classOf[ValueSource[V]],
      generators map (g => Props(classOf[Getter[V]], g)),
      bufferSize)

  case object Get
  case class Value[V](v: V)

  private class Getter[V](generate: () => V) extends Actor {
    import context._

    def receive: Receive = {
      case ValueSource.Get =>
        sender ! ValueSource.Value(generate())
    }
  }
}
