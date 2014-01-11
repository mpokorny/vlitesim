final case class DataFrameHeader(
  val isInvalidData: Boolean,
  val secFromRefEpoch: Int,
  val refEpoch: Int,
  val numberWithinSec: Int,
  val log2NumChannels: Int,
  val lengthBy8: Int,
  val isComplexData: Boolean,
  val bitsPerSampleLess1: Int,
  val threadID: Int,
  val stationID: Int,
  val extendedDataVersion: Int,
  val extendedUserData0: Int,
  val extendedUserData1: Int,
  val extendedUserData2: Int,
  val extendedUserData3: Int
) {
  def isLegacyMode = false
  def version = 0 // TODO: check this value
}
