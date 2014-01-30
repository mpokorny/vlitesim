package edu.nrao.vlite

import scala.collection.JavaConversions._
import org.jnetpcap.{ Pcap, PcapIf }
import akka.util.ByteString

package object pcap {
  implicit class PcapOps(pcap: Pcap) {
    def inject[_](byteString: ByteString): Int = {
      pcap.inject(byteString.compact.asByteBuffer)
    }

    def sendPacket[_](byteString: ByteString): Int = {
      pcap.sendPacket(byteString.compact.asByteBuffer)
    }
  }

  def getMAC(ifname: String): Option[MAC] = {
    val ifs = new java.util.ArrayList[PcapIf]
    val errbuf = new java.lang.StringBuilder
    if (Pcap.findAllDevs(ifs, errbuf) == Pcap.OK)
      ifs find (_.getName == ifname) map { pf =>
        val mac = pf.getHardwareAddress
        MAC(mac(0), mac(1), mac(2), mac(3), mac(4), mac(5))
      }
    else
      None
  }
}
