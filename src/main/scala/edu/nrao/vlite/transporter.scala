package edu.nrao.vlite

import akka.actor.{ Actor, Props }

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
    case Stop =>
      context.become(idle)
    case _ =>
  }

  def idle: Receive = getBufferCount orElse {
    case Start =>
      context.become(transporting)
    case _ =>
  }

  private def getBufferCount: Receive = {
    case GetBufferCount =>
      sender ! BufferCount(bufferCount)
  }

  protected def sendBuffer(buffer: TypedBuffer[_]): Unit
}

final class EthernetTransporter(val device: String, val dst: MAC)
    extends Transporter {
  import edu.nrao.vlite.pcap._
  import Transporter._
  import EthernetTransporter._
  import org.jnetpcap.Pcap

  protected var pcap: Pcap = null

  protected var src: MAC = null

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
    val mtu = new java.io.BufferedReader(
      new java.io.FileReader(s"/sys/class/net/$device/mtu")).readLine.toInt
    if (mtu < 9000)
      context.parent ! OpenWarning("'$device' MTU is small ($mtu)")
    src = getMAC(device).get
  }

  protected lazy val macs = List(
    dst.octet0, dst.octet1, dst.octet2, dst.octet3, dst.octet4, dst.octet5,
    src.octet0, src.octet1, src.octet2, src.octet3, src.octet4, src.octet5).
    toArray
  
  protected def sendBuffer(buffer: TypedBuffer[_]) {
    buffer.byteBuffer.rewind
    buffer.byteBuffer.put(macs)
    pcap.inject(buffer)
  }

  override def toString = s"EthernetTransporter($device)"
}

object EthernetTransporter {
  def props(device: String, dst: MAC): Props =
    Props(classOf[EthernetTransporter], device, dst)

  case class OpenWarning(message: String)

  class OpenException(device: String, cause: String)
      extends Exception(s"Failed to open device '$device': $cause")
}
