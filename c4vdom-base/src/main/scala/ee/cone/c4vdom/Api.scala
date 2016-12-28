
package ee.cone.c4vdom

//import ee.cone.c4connection_api.EventKey
import ee.cone.c4vdom.Types._

trait ToJson {
  def appendJson(builder: MutableJsonBuilder): Unit
}
trait VDomValue extends ToJson

////

trait MutableJsonBuilder {
  def startArray(): MutableJsonBuilder
  def startObject(): MutableJsonBuilder
  def end(): MutableJsonBuilder
  def append(value: String): MutableJsonBuilder
  def append(value: Boolean): MutableJsonBuilder
}

////

object Types {
  type VDomKey = String
}

trait ChildPair[C] {
  def key: VDomKey
}

trait ChildPairFactory {
  def apply[C](key: VDomKey, theElement: VDomValue, elements: List[ChildPair[_]]): ChildPair[C]
}

////

abstract class TagName(val name: String)

trait TagAttr
trait TagStyle extends TagAttr {
  def appendStyle(builder: MutableJsonBuilder): Unit
}

trait Color {
  def value: String
}

////

trait CurrentVDom {
  def fromAlien(state: VDomState, message: Map[String,String]): VDomState
  def toAlien(state: VDomState)(view: ()⇒List[ChildPair[_]]): (VDomState,List[(String,String)])
}

case class VDomState(
    value: VDomValue,
    until: Long,
    hashOfLastView: String, hashFromAlien: String, hashTarget: String,
    ackFromAlien: List[String]
)

trait OnClickReceiver {
  def onClick: Option[VDomState⇒VDomState]
}

trait OnChangeReceiver {
  def onChange: Option[(VDomState,String)⇒VDomState]
}

////

trait TagJsonUtils {
  def appendInputAttributes(builder: MutableJsonBuilder, value: String, deferSend: Boolean): Unit
}