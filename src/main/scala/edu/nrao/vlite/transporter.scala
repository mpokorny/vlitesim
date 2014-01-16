package edu.nrao.vlite

import akka.actor.Actor

object Transporter {
  case object Start
  case object Stop
  case class Transport[T <: Frame[T]](buffer: TypedBuffer[Ethernet[T]])
  case object GetBufferCount
  case class BufferCount(count: Long)
}

trait Transporter extends Actor {
  import Transporter._

  protected var bufferCount: Long = 0L

  def receive = idle

  def transporting: Receive = getBufferCount orElse {
    case Transport(buffer) =>
      sendBuffer(buffer)
      if (bufferCount < Long.MaxValue) bufferCount += 1
      else bufferCount = 0
    case StopTransport =>
      context.become(idle)
    case _ =>
  }

  def idle: Receive = getBufferCount orElse {
    case StartTransport =>
      context.become(transporting)
    case _ =>
  }

  private def getBufferCount: Receive = {
    case GetBufferCount =>
      sender ! BufferCount(bufferCount)
  }

  protected def sendBuffer(buffer: TypedBuffer[_]): Unit
}

final class EthernetTransporter(val device: String) extends Transporter {
  import edu.nrao.vlite.pcap._
  import Transporter._
  import EthernetTransporter._
  import org.jnetpcap.Pcap

  protected var pcap: Pcap = null

  protected var sourceMAC: MAC = null

  override def preStart() {
    val errbuff = new java.lang.StringBuilder("")
    pcap =
      Pcap.openLive(device, 0, Pcap.MODE_NON_PROMISCUOUS, 5 * 1000, errbuff)
    if (pcap == null) {
      val cause = if (errbuff.length > 0) errbuff.toString else "unknown cause"
      throw new OpenException(device, cause)
    } else if (errbuff.length > 0) {
      context.parent ! OpenWarning(errbuff.toString)
    }
    sourceMAC = getMAC(device).get
  }

  protected def sendBuffer(buffer: TypedBuffer[_]) {
    buffer.byteBuffer.position(6)
    buffer.byteBuffer.
      put(sourceMAC.octet0).
      put(sourceMAC.octet1).
      put(sourceMAC.octet2).
      put(sourceMAC.octet3).
      put(sourceMAC.octet4).
      put(sourceMAC.octet5)
    pcap.inject(buffer)
  }

  override def toString = s"EthernetTransporter($device)"
}

object EthernetTransporter {
  case class OpenWarning(message: String)

  class OpenException(device: String, cause: String)
      extends Exception(s"Failed to open device '$device': $cause")
}
