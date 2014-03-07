package edu.nrao.vlite

import akka.actor._

abstract class ValueSourceBase[V] extends Actor {

  val bufferSize: Int

  def requestValues(n: Int): Unit

  def receiveValues(as: Vector[Any]): Vector[V]

  val valueRatio: (Int, Int) // this actor's request to value ratio

  private var buffer: Vector[V] = Vector.empty

  private var pendingGets: Vector[(ActorRef, Int)] = Vector.empty

  private var pendingReceives: Int = 0

  private def ceil(n: Int, d: Int) =
    (n + (d - n % d) % d) / d

  private def requestFullBuffer() {
    val eventualLength = buffer.length + pendingReceives
    val deficit = bufferSize - eventualLength
    if (deficit > 0) {
      requestValues(numRequests(deficit))
      pendingReceives += deficit
    }
  }

  private def numRequests(numValues: Int) = 
    if (numValues > 0)
      valueRatio match { case (r, v) => ceil(numValues * r, v) }
    else
      0

  private def sendToPendingGets() {
    pendingGets match {
      case Vector() =>
        requestFullBuffer()
      case (actor, numValues) +: ps =>
        if (buffer.length > numValues) {
          pendingGets = ps
          fulfillRequest(actor, numValues)
          sendToPendingGets()
        } else {
          requestFullBuffer()
        }
    }
  }

  private def fulfillRequest(to: ActorRef, n: Int) {
    if (n > 0)
      buffer.splitAt(n) match {
        case (vs, rem) =>
          to ! ValueSource.Values(vs)
          buffer = rem
      }
    else
      to ! ValueSource.Values(Vector.empty[V])
  }

  private def addPendingGet(to: ActorRef, numValues: Int) {
    val nv = numValues max 0
    requestValues(numRequests(nv))
    pendingGets = pendingGets :+ ((to, nv))
    pendingReceives += nv
  }

  override def preStart() {
    requestFullBuffer()
  }

  def receive: Receive = {
    case ValueSource.Get(n) =>
      addPendingGet(sender, n)
      sendToPendingGets()
    case ValueSource.Values(vs) =>
      val newVs = receiveValues(vs)
      pendingReceives -= newVs.length
      buffer ++= newVs
      sendToPendingGets()
  }
}

class ValueSource[V](sourceProps: Props, val bufferSize: Int)
    extends ValueSourceBase[V] {

  val valueRatio = (1, 1)

  val source = context.actorOf(sourceProps)

  def requestValues(n: Int) {
    source ! ValueSource.Get(n)
  }

  def receiveValues(as: Vector[Any]): Vector[V] =
    as.asInstanceOf[Vector[V]]
}

object ValueSource {
  def props[V](generate: () => V, bufferSize: Int): Props =
    props(Props(classOf[Getter[V]], generate), bufferSize)

  def props[V](sourceProps: Props, bufferSize: Int): Props =
    Props(classOf[ValueSource[V]], sourceProps, bufferSize)

  case class Get(n: Int)
  case class Values[V](v: Vector[V])

  private class Getter[V](generate: () => V) extends Actor {
    import context._

    def receive: Receive = {
      case ValueSource.Get(n) =>
        sender ! ValueSource.Values((0 until n).toVector map (_ => generate()))
    }
  }
}
