package edu.nrao.vlite

import akka.actor.{ Actor, ActorRef, ActorLogging, Props, Terminated }
import java.net.InetSocketAddress

object Transporter {
  case class Transport[T <: Frame[T]](buffer: TypedBuffer[Ethernet[T]])
  case object GetBufferCount
  case class BufferCount(count: Long)
  case class OpenWarning(message: String)
  case class OpenException(cause: String) extends Exception(cause)
}

trait Transporter extends Actor with ActorLogging {
  import Transporter._

  protected var bufferCount: Long = 0L

  def receive: Receive = {
    case Transport(buffer) =>
      if (sendBuffer(buffer)) {
        if (bufferCount < Long.MaxValue) bufferCount += 1
        else bufferCount = 0
      }
    case GetBufferCount =>
      sender ! BufferCount(bufferCount)
  }

  protected def sendBuffer(buffer: TypedBuffer[_]): Boolean
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
      throw new OpenException(s"Failed to open device '$device': $cause")
    } else if (errbuff.length > 0) {
      context.parent ! OpenWarning(errbuff.toString)
    }
    val mtu = new java.io.BufferedReader(
      new java.io.FileReader(s"/sys/class/net/$device/mtu")).readLine.toInt
    if (mtu < 9000)
      context.parent ! OpenWarning(s"'$device' MTU is small ($mtu)")
    src = getMAC(device).get
  }

  protected lazy val macs = List(
    dst.octet0, dst.octet1, dst.octet2, dst.octet3, dst.octet4, dst.octet5,
    src.octet0, src.octet1, src.octet2, src.octet3, src.octet4, src.octet5).
    toArray
  
  protected def sendBuffer(buffer: TypedBuffer[_]) = {
    buffer.byteBuffer.rewind
    buffer.byteBuffer.put(macs)
    pcap.sendPacket(buffer) == 0
  }

  override def toString = s"EthernetTransporter($device)"
}

object EthernetTransporter {
  def props(device: String, dst: MAC): Props =
    Props(classOf[EthernetTransporter], device, dst)
}

final class UdpTransporter(val dst: InetSocketAddress)
    extends Transporter {
  import Transporter._
  import java.nio.channels.DatagramChannel
  import context._

  protected var channel: Option[DatagramChannel] = None

  override def preStart() {
    channel = try {
      Some(DatagramChannel.open().connect(dst))
    } catch {
      case e: Exception =>
        parent ! OpenException(e.getMessage)
        None
    }
  }

  override def postStop() {
    channel foreach (_.disconnect())
  }

  protected def sendBuffer(buffer: TypedBuffer[_]) = {
    (channel.map { ch =>
      val b = buffer.byteBuffer
      b.rewind
      ch.write(b) == b.limit
    }).getOrElse(false)
  }

  override def toString = s"UdpTransporter($dst)"
}

object UdpTransporter {
  def props(dst: InetSocketAddress): Props =
    Props(classOf[UdpTransporter], dst)
}
