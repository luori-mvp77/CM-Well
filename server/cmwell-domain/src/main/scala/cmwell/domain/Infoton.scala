/**
  * © 2019 Refinitiv. All Rights Reserved.
  *
  * Licensed under the Apache License, Version 2.0 (the “License”); you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  *   http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
  * an “AS IS” BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  *
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */
package cmwell.domain

import cmwell.syntaxutils._
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util

import com.typesafe.scalalogging.LazyLogging
import org.joda.time.{DateTime, DateTimeZone}
import org.joda.time.format.{DateTimeFormat, ISODateTimeFormat}

import scala.language.implicitConversions
import scala.collection.immutable.SortedSet
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
  * User: israel
  * Date: 10/1/14
  * Time: 17:25
  */
trait Formattable
trait Jsonable

object Infoton {
  def getParent(path: String): String = {
    // check if we are in /
    if (path.endsWith("/") & path.length == 1)
      "$root"
    else {
      val p = path.endsWith("/") match {
        case true  => path.take(path.take(path.length - 1).lastIndexOf("/"))
        case false => path.take(path.lastIndexOf("/"))
      }

      if (p.isEmpty)
        "/"
      else {
        var index = p.length - 1
        while (p(index) == '/') {
          index = index - 1
        }
        if (index == p.length - 1)
          p
        else {
          p.take(index + 1)
        }
      }
    }
  }

  val https = Some("https")
}

case class SystemFields (path: String, lastModified: DateTime, lastModifiedBy: String,
                         dc: String, indexTime: Option[Long], indexName: String, protocol: String){
  val name = path.drop(path.lastIndexOf("/") + 1)
}

sealed trait Infoton extends Formattable { self =>
  def kind = self.getClass.getSimpleName
  def systemFields: SystemFields
  def fields: Option[Map[String, Set[FieldValue]]] = None
  def extraBytesForDigest: Seq[Array[Byte]] = Seq.empty
  def extraLengthForWeight: Long = 0

  def copyInfoton(systemFields: SystemFields = this.systemFields,
                  fields: Option[Map[String, Set[FieldValue]]] = this.fields): Infoton = this match {
    case oi: ObjectInfoton =>
      oi.copy(systemFields = systemFields, fields = fields)
    case fi: FileInfoton =>
      fi.copy(systemFields = systemFields, fields = fields)
    case li: LinkInfoton =>
      li.copy(systemFields = systemFields, fields = fields)
    case di: DeletedInfoton =>
      di.copy(systemFields = systemFields)
    case ci: CompoundInfoton =>
      ci.copy(systemFields = systemFields, fields = fields)
    case gi: GhostInfoton => gi.copy(systemFields = systemFields)
  }

  def overrideUuid(forcedUuid: String) = this match {
    case oi: ObjectInfoton =>
      new ObjectInfoton(oi.systemFields, oi.fields) {
        override def uuid = forcedUuid
      }
    case fi: FileInfoton =>
      new FileInfoton(fi.systemFields, fi.fields, fi.content) {
        override def uuid = forcedUuid
      }
    case li: LinkInfoton =>
      new LinkInfoton(li.systemFields, li.fields, li.linkTo, li.linkType) {
        override def uuid = forcedUuid
      }
    case di: DeletedInfoton =>
      new DeletedInfoton(di.systemFields) {
        override def uuid = forcedUuid }
    case ci: CompoundInfoton =>
      new CompoundInfoton(ci.systemFields,
                          ci.fields,
                          ci.children,
                          ci.offset,
                          ci.length,
                          ci.total) { override def uuid = forcedUuid }
    case gi: GhostInfoton => new GhostInfoton(gi.systemFields) {
      override def uuid = forcedUuid }
  }

  def uuid = uuid_
  final def weight = weight_
  final def parent = parent_

  def replaceIndexTime(newIndextime: Option[Long]): Infoton = copyInfoton(systemFields.copy(indexTime = newIndextime))

  /* calculate uuid and weight */
  def longToByteArray(l: Long): Array[Byte] = {
    val bb = ByteBuffer.allocate(8)
    bb.putLong(l)
    bb.flip()
    bb.array()
  }

  /* internal variables and their counterparty methods for calculated fields*/
  private var parent_ = ""
  private val (uuid_, weight_) = {
    var weight_ = 0L
    val digest = MessageDigest.getInstance("MD5")
    val pathBytes_ = systemFields.path.getBytes("UTF-8")
    digest.update(pathBytes_)
    weight_ += pathBytes_.length
    val lastModifiedBytes_ = longToByteArray(systemFields.lastModified.getMillis)
    digest.update(lastModifiedBytes_)
    weight_ += lastModifiedBytes_.length
    fields.foreach { f =>
      f.map { case (k, v) => (k, SortedSet(v.map(_.payload).toSeq: _*)) }.toSeq.sortBy(_._1).foreach {
        case (k, v) =>
          val keyBytes_ = k.getBytes("UTF-8")
          digest.update(keyBytes_)
          weight_ += keyBytes_.length
          v.foreach { q =>
            val valueBytes_ = q.getBytes("UTF-8")
            digest.update(valueBytes_)
            weight_ += valueBytes_.length
          }
      }
    }
    extraBytesForDigest.foreach { bytes =>
      digest.update(bytes)
      weight_ += bytes.length
    }
    val uuid_ = digest.digest().map("%02x".format(_)).mkString
    weight_ += extraLengthForWeight
    (uuid_, weight_)
  }

  /* calculate parent*/
  parent_ = Infoton.getParent(systemFields.path)

  // check if we are in /
  /*
  if ( path.endsWith("/") & path.length == 1 )
    parent_ = "$root"
  else {
    val p = path.endsWith("/") match {
      case true => path.take(path.take(path.length-1).lastIndexOf("/"))
      case false => path.take(path.lastIndexOf("/"))
    }

    if ( p.isEmpty )
      parent_ = "/"
    else {
      var index = p.length - 1
      while ( p(index) == '/') {
        index = index - 1
      }
      if ( index == p.length - 1)
        parent_ = p
      else {ObjectInfoton(path:String, lastModified:DateTime = new DateTime, override val fields:Option[Map[String, Set[FieldValue]]] = None) extends Infoton
        parent_ = p.take(index + 1)
      }
    }
  }
   */

  def isSameAs(that: Infoton) = {
    this.uuid == that.uuid || (
      this.kind == that.kind &&
      this.systemFields.protocol == that.systemFields.protocol &&
      this.fields == that.fields &&
      this.extraBytesForDigest == that.extraBytesForDigest
    )
  }
  //  def ⊆(that: Infoton) = (this.fields, that.fields) match {
  //    case (Some(f1),Some(f2)) =>
  //      val (f1s,f2s) = (f1.toSeq,f2.toSeq)
  //      f1s.intersect(f2s) == f1s
  //    case (None,_) => true
  //    case _ => false
  //  }

  def masked(fieldsMask: Set[String], allowEmpty: Boolean = false): Infoton =
    if (fieldsMask.isEmpty && !allowEmpty) this else getMasked(fieldsMask)
  protected def getMasked(fieldsMask: Set[String]): Infoton
  protected def maskedFields(fieldsMask: Set[String]) = fields.map(flds => flds -- flds.keySet.diff(fieldsMask))
}

case class ObjectInfoton(systemFields: SystemFields, override val fields: Option[Map[String, Set[FieldValue]]] = None)
    extends Infoton {
  override def getMasked(fieldsMask: Set[String]): Infoton = {
    val originalUuid = uuid
    new ObjectInfoton(systemFields, maskedFields(fieldsMask)) {
      override val uuid = originalUuid
      override def kind = "ObjectInfoton"
    }
  }
}

object ObjectInfoton {
  def apply(systemFields: SystemFields, fields: Map[String, Set[FieldValue]]) =
    new ObjectInfoton(systemFields, Some(fields))

  def apply(path: String, lastModified: DateTime, lastModifiedBy: String, dc: String, indexTime: Option[Long], indexName: String, protocol: String,
            fields: Map[String, Set[FieldValue]]) =
    new ObjectInfoton(SystemFields(path, lastModified, lastModifiedBy, dc, indexTime,  indexName, protocol), Some(fields))
}

case class CompoundInfoton(systemFields: SystemFields,
                           override val fields: Option[Map[String, Set[FieldValue]]] = None,
                           children: Seq[Infoton],
                           offset: Long,
                           length: Long,
                           total: Long)
    extends Infoton {

  override def getMasked(fieldsMask: Set[String]): Infoton = {
    val originalUuid = uuid
    new CompoundInfoton(systemFields, maskedFields(fieldsMask), children, offset, length, total) {
      override val uuid = originalUuid
      override def kind = "CompoundInfoton"
    }
  }
}

object CompoundInfoton {
  def apply(systemFields: SystemFields, fields: Map[String, Set[FieldValue]], children: Seq[Infoton], offset: Long, length: Long, total: Long) =
    new CompoundInfoton(systemFields, Some(fields), children, offset, length, total)

  def apply(path: String, lastModified: DateTime, lastModifiedBy: String,
            dc: String, indexTime: Option[Long], indexName: String, protocol: String,
            fields: Map[String, Set[FieldValue]], children: Seq[Infoton], offset: Long, length: Long, total: Long) =
    new CompoundInfoton(SystemFields(path, lastModified, lastModifiedBy, dc, indexTime,  indexName, protocol), Some(fields),
      children, offset, length, total)
}

object LinkType {
  val Permanent: Int = 0
  val Temporary: Int = 1
  val Forward: Int = 2
}

case class LinkInfoton(systemFields: SystemFields,
                       override val fields: Option[Map[String, Set[FieldValue]]] = None,
                       linkTo: String,
                       linkType: Int)
    extends Infoton {

  override def extraBytesForDigest: Seq[Array[Byte]] = {
    Seq(linkTo.getBytes("UTF-8"), linkType.toString.getBytes("UTF-8"))
  }

  override def extraLengthForWeight: Long = linkTo.getBytes.length + 1

  override def getMasked(fieldsMask: Set[String]): Infoton = {
    val originalUuid = uuid
    new LinkInfoton(systemFields, maskedFields(fieldsMask), linkTo, linkType) {
      override val uuid = originalUuid
      override def kind = "LinkInfoton"
    }
  }
}

object LinkInfoton {
  def apply(systemFields: SystemFields, fields: Map[String, Set[FieldValue]], linkTo: String, linkType: Int) =
    new LinkInfoton(systemFields, Some(fields), linkTo, linkType)

  def apply(path: String, lastModified: DateTime, lastModifiedBy: String, dc: String, indexTime: Option[Long], indexName: String, protocol: String,
            fields: Map[String, Set[FieldValue]],linkTo: String, linkType: Int) =

    new LinkInfoton(SystemFields(path, lastModified, lastModifiedBy, dc, indexTime, indexName, protocol), Some(fields), linkTo, linkType)
}

case class DeletedInfoton(systemFields: SystemFields)
    extends Infoton {
  override def getMasked(fieldsMask: Set[String]): Infoton = this
}

object DeletedInfoton {
  def apply(systemFields: SystemFields, fields: Map[String, Set[FieldValue]]) =
    new DeletedInfoton(systemFields)

  def apply(path: String, lastModified: DateTime, lastModifiedBy: String,
            dc: String, indexTime: Option[Long], indexName: String, protocol: String) =
    new DeletedInfoton(SystemFields(path, lastModified, lastModifiedBy, dc, indexTime, indexName, protocol))
}

case class GhostInfoton(systemFields: SystemFields) extends Infoton {
  override protected def getMasked(fieldsMask: Set[String]): Infoton = this
}

object GhostInfoton {
  val zeroTime = new DateTime(0L)
  def apply(systemFields: SystemFields) = new GhostInfoton(systemFields)

  def apply(path: String, dc: String = "N/A", indexTime: Option[Long] = None, lastModified: DateTime = GhostInfoton.zeroTime,
            lastModifiedBy: String = "", protocol: String, indexName: String = "") =
    new GhostInfoton(SystemFields(path, lastModified, lastModifiedBy, dc, indexTime,  indexName, protocol))

  def ghost(path: String, protocol: String): Infoton = GhostInfoton(SystemFields(path, zeroTime, "anonymous", "N/A", None, "", protocol))
}

case class FileInfoton(systemFields: SystemFields,
                       override val fields: Option[Map[String, Set[FieldValue]]] = None,
                       content: Option[FileContent] = None)
    extends Infoton {

  def hasData = content.exists(_.data.isDefined)
  def hasDataPointer = content.exists(_.dataPointer.isDefined)

  override def extraBytesForDigest: Seq[Array[Byte]] = {
    val dataRepr = if (content.exists(_.dataPointer.isDefined)) {
      Seq(content.get.dataPointer.get.getBytes("UTF-8"))
    } else {
      val d = content.flatMap(_.data).getOrElse(Array.emptyByteArray)
      Seq(longToByteArray(d.length), d)
    }

    content.fold(Seq.empty[Array[Byte]]) { c =>
      val mime = c.mimeType.getBytes("UTF-8")
      mime +: dataRepr
    }
  }

  override def extraLengthForWeight = {
    content
      .map { c =>
        c.data.map { _.length.toLong }.getOrElse(0L)
      }
      .getOrElse(0L)
  }

  override def getMasked(fieldsMask: Set[String]): Infoton = {
    val originalUuid = uuid
    new FileInfoton(systemFields, maskedFields(fieldsMask), content) {
      override val uuid = originalUuid
      override def kind = "FileInfoton"
    }
  }

  def withoutData: FileInfoton = {
    require(content.isDefined && (content.get.data.isDefined || content.get.dataPointer.isDefined),
            "content must be defined with either data or dataPointer")
    val originalUuid = uuid
    val hash = content.flatMap(_.dataPointer).orElse(content.flatMap(_.data).map(cmwell.util.string.Hash.sha1))
    new FileInfoton(systemFields,
                    fields,
                    content.map(c => FileContent(None, c.mimeType, content.get.dataLength, hash))) {
      override val uuid = originalUuid
      override def kind = "FileInfoton"
    }
  }

  def populateDataFromPointerBy(
    fetchFunc: (String) => Future[Array[Byte]]
  )(implicit ec: ExecutionContext): Future[FileInfoton] = {
    require(content.isDefined && content.get.dataPointer.isDefined, "dataPointer must exist")
    val originalUuid = uuid
    val hashOpt = content.flatMap(_.dataPointer)
    val dataFut = content.flatMap(_.data).map(Future.successful).getOrElse(fetchFunc(hashOpt.get))
    dataFut.map(
      data =>
        new FileInfoton(systemFields,
                        fields,
                        content.map(c => FileContent(Some(data), c.mimeType, data.length, hashOpt))) {
          override val uuid = originalUuid
          override def kind = "FileInfoton"
      }
    )
  }
}

object FileInfoton {
  def apply(systemFields: SystemFields,
            fields: Map[String, Set[FieldValue]],
            content: FileContent) =
    new FileInfoton(systemFields, Some(fields), Some(content))

  def apply(path: String, lastModified: DateTime, lastModifiedBy: String,
            dc: String, indexTime: Option[Long], indexName: String, protocol: String,
            fields: Map[String, Set[FieldValue]],
            content: FileContent) =
    new FileInfoton(SystemFields(path, lastModified, lastModifiedBy, dc, indexTime,  indexName, protocol), Some(fields), Some(content))
}

case class FileContent(data: Option[Array[Byte]],
                       mimeType: String,
                       dataLength: Int,
                       dataPointer: Option[String] = None) {

  override def equals(other: Any) = other match {
    case fc: FileContent =>
      util.Arrays.equals(this.data.orNull, fc.data.orNull) && this.mimeType.equals(fc.mimeType) && this.dataPointer
        .equals(fc.dataPointer)
    case _ => false
  }

  def length = data.fold(dataLength)(_.length)

  override def hashCode() = 0
  def asString: String = { new String(data.getOrElse(Array[Byte]()), "UTF-8") }
}

object FileContent {
  def apply(data: Array[Byte], mimeType: String) = new FileContent(Some(data), mimeType, data.length)
  def apply(mimeType: String, length: Long) = new FileContent(None, mimeType, length.toInt)
}

case class VirtualInfoton(infoton: Infoton) {
  require(!infoton.isInstanceOf[VirtualInfoton], "youtube.com/watch?v=v2FMqtC1x9Y")

  def getInfoton = infoton match {
    case ObjectInfoton(systemFields, fields) =>
      new ObjectInfoton(systemFields, fields) {
        override def kind = "VirtualObjectInfoton"
        override def uuid = "0"
      }
    case CompoundInfoton(systemFields, fields, children, offset, length, total) =>
      new CompoundInfoton(systemFields, fields, children, offset, length, total) {
        override def kind = "VirtualCompoundInfoton"
        override def uuid = "0"
      }
    case LinkInfoton(systemFields, fields, linkTo, linkType) =>
      new LinkInfoton(systemFields, fields, linkTo, linkType) {
        override def kind = "VirtualLinkInfoton"
        override def uuid = "0"
      }
    case FileInfoton(systemFields, fields, content) =>
      new FileInfoton(systemFields, fields, content) {
        override def kind = "VirtualFileInfoton"
        override def uuid = "0"
      }
    case _ => ???
  }
}

object VirtualInfoton {
  implicit def v2i(v: VirtualInfoton): Infoton = v.getInfoton
}

case class BagOfInfotons(infotons: Seq[Infoton]) extends Formattable {
  def masked(fieldsMask: Set[String]): BagOfInfotons = BagOfInfotons(infotons.map(_.masked(fieldsMask)))
}
case class RetrievablePaths(infotons: Seq[Infoton], irretrievablePaths: Seq[String]) extends Formattable {
  def masked(fieldsMask: Set[String]): RetrievablePaths = copy(infotons = infotons.map(_.masked(fieldsMask)))
}
case class InfotonHistoryVersions(versions: Seq[Infoton]) extends Formattable {
  def masked(fieldsMask: Set[String]): InfotonHistoryVersions =
    InfotonHistoryVersions(versions.map(_.masked(fieldsMask)))
}

case class InfotonPaths(paths: Seq[String]) extends Formattable

object ContentPortion {
  def everything(infoton: Infoton): ContentPortion = Everything(infoton)
  def unknownNestedContent(infoton: Infoton): ContentPortion = UnknownNestedContent(infoton)
}
sealed trait ContentPortion { def infoton: Infoton }
case class UnknownNestedContent(infoton: Infoton) extends ContentPortion
case class Everything(infoton: Infoton) extends ContentPortion

final class ComparisonImpossible private (val valueType: String, val input: String, cause: Throwable)
    extends Exception(s"can't compare [$input] with values of type [$valueType]", cause)
object ComparisonImpossible {
  def apply(valueType: String, input: String): ComparisonImpossible = new ComparisonImpossible(valueType, input, null)
  def apply(valueType: String, input: String, cause: Throwable): ComparisonImpossible =
    new ComparisonImpossible(valueType, input, cause)
  def unapply(ex: ComparisonImpossible): Option[(String, String, Option[Throwable])] =
    Some((ex.valueType, ex.input, Option(ex.getCause)))
}

sealed trait FieldValue {
  def value: Any
  def size: Long
  def quad: Option[String]
  def sType: String = this.getClass.getSimpleName.substring(1)
  def compareToString(unparsedValue: String): Try[Int]
  private[domain] def payload: String = s"${this.getClass.getName}$value${quad.getOrElse("")}"
}

case class FNull(quad: Option[String]) extends FieldValue {
  def value = null
  def size = 0
  override def compareToString(unparsedValue: String): Try[Int] = Failure(ComparisonImpossible("FNull", unparsedValue))
}

case class FExtra[T](value: T, quad: Option[String]) extends FieldValue {
  def size = 0
  override def toString(): String = value.toString
  override def compareToString(unparsedValue: String): Try[Int] = Failure(ComparisonImpossible("FExtra", unparsedValue))
}

object FieldValue {

  def prefixByType(fValue: FieldValue): Char = fValue match {
    case _: FString | _: FReference | _: FExternal => 's'
    case _: FInt                                   => 'i'
    case _: FLong | _: FBigInt                     => 'l'
    case _: FBigDecimal | _: FDouble               => 'w'
    case _: FBoolean                               => 'b'
    case _: FDate                                  => 'd'
    case _: FFloat                                 => 'f'
    case _: FNull                                  => !!!
    case _: FExtra[_]                              => !!!
  }

  def parseString(s: String): FieldValue = {
    if (FReference.isUriRef(s)) FReference(s, None)
    else if (FDate.isDate(s)) FDate(s, None)
    else FString(s, None, None)
  }

  def apply(value: String, dataTypeURI: String): FieldValue = this.apply(value, dataTypeURI, None)
  def apply(value: String, dataTypeURI: String, quad: Option[String]): FieldValue = FExternal(value, dataTypeURI, quad)
  def apply(num: Int): FieldValue = this.apply(num, None)
  def apply(num: Int, quad: Option[String]): FieldValue = FInt(num, quad)
  def apply(num: Long): FieldValue = this.apply(num, None)
  def apply(num: Long, quad: Option[String]): FieldValue = FLong(num, quad)
  def apply(num: java.math.BigInteger): FieldValue = this.apply(num, None)
  def apply(num: java.math.BigInteger, quad: Option[String]): FieldValue = FBigInt(num, quad)
  def apply(num: Float): FieldValue = this.apply(num, None)
  def apply(num: Float, quad: Option[String]): FieldValue = FFloat(num, quad)
  def apply(num: Double): FieldValue = this.apply(num, None)
  def apply(num: Double, quad: Option[String]): FieldValue = FDouble(num, quad)
  def apply(num: java.math.BigDecimal): FieldValue = this.apply(num, None)
  def apply(num: java.math.BigDecimal, quad: Option[String]): FieldValue = FBigDecimal(num, quad)
  def apply(bool: Boolean): FieldValue = this.apply(bool, None)
  def apply(bool: Boolean, quad: Option[String]): FieldValue = FBoolean(bool, quad)
  def apply(str: String): FieldValue = this.apply(str, None, None)
  def apply(str: String, lang: Option[String], quad: Option[String]): FieldValue = FString(str, lang, quad)
}

case class FInt(value: Int, quad: Option[String]) extends FieldValue {
  override def toString(): String = value.toString
  override def size: Long = 4
  override def compareToString(unparsedValue: String): Try[Int] =
    Try(Integer.parseInt(unparsedValue))
      .transform(parsedValue => Try(value.compare(parsedValue)),
                 cause => Failure(ComparisonImpossible("FInt", unparsedValue, cause)))
}

object FInt {
  def apply(n: Int): FInt = FInt(n, None)
}

case class FLong(value: Long, quad: Option[String]) extends FieldValue {
  override def toString(): String = value.toString
  override def size: Long = 8
  override def compareToString(unparsedValue: String): Try[Int] =
    Try(java.lang.Long.parseLong(unparsedValue))
      .transform(parsedValue => Try(value.compare(parsedValue)),
                 cause => Failure(ComparisonImpossible("FLong", unparsedValue, cause)))
}

object FLong {
  def apply(n: Long): FLong = FLong(n, None)
}

case class FBigInt(value: java.math.BigInteger, quad: Option[String]) extends FieldValue {
  override def toString(): String = value.toString
  override def size: Long = value.bitLength()
  override def compareToString(unparsedValue: String): Try[Int] =
    Try(BigInt(unparsedValue).underlying())
      .transform(parsedValue => Try(value.compareTo(parsedValue)),
                 cause => Failure(ComparisonImpossible("FBigInt", unparsedValue, cause)))
}

object FBigInt {
  def apply(n: java.math.BigInteger): FBigInt = FBigInt(n, None)
}

case class FFloat(value: Float, quad: Option[String]) extends FieldValue {
  override def toString(): String = value.toString
  override def size: Long = 4
  override def compareToString(unparsedValue: String): Try[Int] =
    Try(java.lang.Float.parseFloat(unparsedValue))
      .transform(parsedValue => Try(value.compare(parsedValue)),
                 cause => Failure(ComparisonImpossible("FFloat", unparsedValue, cause)))
}

object FFloat {
  def apply(n: Float): FFloat = FFloat(n, None)
}

case class FDouble(value: Double, quad: Option[String]) extends FieldValue {
  override def toString(): String = value.toString
  override def size: Long = 8
  override def compareToString(unparsedValue: String): Try[Int] =
    Try(java.lang.Double.parseDouble(unparsedValue))
      .transform(parsedValue => Try(value.compare(parsedValue)),
                 cause => Failure(ComparisonImpossible("FDouble", unparsedValue, cause)))
}

object FDouble {
  def apply(n: Double): FDouble = FDouble(n, None)
}

case class FBigDecimal(value: java.math.BigDecimal, quad: Option[String]) extends FieldValue {
  override def toString(): String = value.toString
  override def size: Long = value.precision
  override def compareToString(unparsedValue: String): Try[Int] =
    Try(BigDecimal(unparsedValue).underlying())
      .transform(parsedValue => Try(value.compareTo(parsedValue)),
                 cause => Failure(ComparisonImpossible("FBigDecimal", unparsedValue, cause)))
}

object FBigDecimal {
  def apply(n: java.math.BigDecimal): FBigDecimal = FBigDecimal(n, None)
}

case class FExternal(value: String, dataTypeURI: String, quad: Option[String]) extends FieldValue {
  require(dataTypeURI.forall(_ != '$'))
  override def toString(): String = value
  override def size: Long = value.getBytes("UTF-8").length + dataTypeURI.getBytes("UTF-8").length
  def getDataTypeURI: String =
    if (dataTypeURI.take(4) == "xsd#")
      "http://www.w3.org/2001/XMLSchema" + dataTypeURI.drop(3)
    else dataTypeURI

  override def compareToString(unparsedValue: String): Try[Int] =
    Try(Ordering.String.compare(value, unparsedValue))
}

object FExternal {
  def apply(s: String, u: String): FExternal = FExternal(s, u, None)
}

case class FString(value: String, lang: Option[String], quad: Option[String]) extends FieldValue {
  override def toString(): String = value
  override def size: Long = value.getBytes("UTF-8").size

  override def compareToString(unparsedValue: String): Try[Int] =
    Try(Ordering.String.compare(value, unparsedValue))
}

object FString {
  def apply(s: String): FString = FString(s, None, None)
}

case class FReference(value: String, quad: Option[String]) extends FieldValue with LazyLogging {
  override def toString(): String = value
  override def size: Long = value.getBytes("UTF-8").size
  def getCmwellPath: String =
    if (value.startsWith("https:/")) value.drop("https:/".length)
    else if (value.startsWith("cmwell://")) value.drop("cmwell:/".length)
    else if (value.startsWith("http:/")) value.drop("http:/".length)
    else {
      logger.warn(
        s"value [$value] has bad prefix, and is not a CM-Well reference (though it is a field value of type FReference)."
      )
      value
    }

  override def compareToString(unparsedValue: String): Try[Int] =
    Try(Ordering.String.compare(value, unparsedValue))

  def getProtocol: String = value.takeWhile(':'.!=)
}

object FReference {
  def apply(s: String): FReference = FReference(s, None)
  def isUriRef(s: String): Boolean = scala.util.Try { new java.net.URL(s) }.isSuccess || s.startsWith("cmwell://")
}

case class FBoolean(value: Boolean, quad: Option[String]) extends FieldValue {
  override def toString(): String = value.toString
  override def size: Long = 4

  override def compareToString(unparsedValue: String): Try[Int] =
    Try(java.lang.Boolean.parseBoolean(unparsedValue))
      .transform(parsedValue => Try(value.compare(parsedValue)),
                 cause => Failure(ComparisonImpossible("FBoolean", unparsedValue, cause)))
}

object FBoolean {
  def apply(b: Boolean): FBoolean = FBoolean(b, None)
}

//TODO: inner value should be DateTime. not String! companion object apply method should convert String input into DateTime instances.
final class FDate(private val temp: String, val quad: Option[String]) extends FieldValue {

  val (value, inner) = FDate.stringToDate(temp) match {
    case scala.util.Success(d) => temp -> d
    case scala.util.Failure(e) => {
      val v = FDate.fixFormattingIfNeeded(temp)
      FDate.stringToDate(v) match {
        case scala.util.Success(d) => v -> d
        case scala.util.Failure(e) => throw e
      }
    }
  }

  def canEqual(a: Any) = a != null && a.isInstanceOf[FDate]

  override def equals(that: Any): Boolean = that match {
    case that: FDate => that.canEqual(this) && that.value == this.value && that.quad == this.quad
    case _           => false
  }

  override def hashCode: Int = 37 * value.## + quad.##

  override def toString(): String = value
  def getDate: DateTime = inner
  override def size: Long = value.getBytes("UTF-8").size

  override def compareToString(unparsedValue: String): Try[Int] = {
    FDate
      .stringToDate(unparsedValue)
      .recoverWith {
        case e: Throwable => {
          val v = FDate.fixFormattingIfNeeded(temp)
          FDate.stringToDate(v).recoverWith { case _ => Failure(e) }
        }
      }
      .transform(parsedValue => Try(inner.compareTo(parsedValue)),
                 cause => Failure(ComparisonImpossible("FDate", unparsedValue, cause)))
  }
}

object FDate extends LazyLogging {

  def apply(s: String): FDate = new FDate(s, None)
  def apply(s: String, q: Option[String]): FDate = new FDate(s, q)
  def unapply(fDate: FDate): Option[(String, Option[String])] = Some(fDate.value -> fDate.quad)

  private val withDotdateParser = ISODateTimeFormat.dateTimeParser().withZone(DateTimeZone.UTC)
  private val withoutDotdateParser = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(DateTimeZone.UTC)
  private val justDateParser = DateTimeFormat.forPattern("yyyy-MM-dd").withZone(DateTimeZone.UTC)
  private val FixableDate = """(\d{4}-\d{2}-\d{2})\s*T?\s*(\d{2}:\d{2}:\d{2})(.\d+)?\s*Z?\s*""".r

  def fixFormattingIfNeeded(str: String) = str match {
    case FixableDate(date, time, millis) => date + "T" + time + Option(millis).getOrElse("") + "Z"
    case _                               => str // unfixable
  }

  def isDate(str: String): Boolean = stringToDateWithFixTry(str).isSuccess

  private def stringToDateWithFixTry(str: String): Try[DateTime] = {
    val orig = stringToDate(str)
    orig.recoverWith {
      case e => {
        val fixed = fixFormattingIfNeeded(str)
        if (fixed == str) orig
        else {
          logger.warn(s"fixing a date: [$str] to [$fixed]", e)
          stringToDate(fixed)
        }
      }
    }
  }

  private def stringToDate(str: String): Try[DateTime] = {
    val orig = Try(withDotdateParser.parseDateTime(str))
    orig.recoverWith {
      case _ =>
        Try(withoutDotdateParser.parseDateTime(str)).recoverWith {
          case _ => Try(justDateParser.parseDateTime(str))
        }
    }
  }
}
