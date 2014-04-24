//
// Copyright © 2014 Associated Universities, Inc. Washington DC, USA.
//
// This file is part of vlitesim.
//
// vlitesim is free software: you can redistribute it and/or modify it under the
// terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version.
//
// vlitesim is distributed in the hope that it will be useful, but WITHOUT ANY
// WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
// A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along with
// vlitesim.  If not, see <http://www.gnu.org/licenses/>.
//
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
