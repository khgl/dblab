package ch.epfl.data
package dblab.legobase
package optimization

import schema._
import scala.language.implicitConversions
import sc.pardis.ir._
import reflect.runtime.universe.{ TypeTag, Type }
import sc.pardis.optimization._
import deep._
import sc.pardis.types._
import sc.pardis.types.PardisTypeImplicits._
import sc.pardis.shallow.utils.DefaultValue

/**
 * A transformer for partitioning and indexing the arrays whenever possible.
 *
 * Given an input program first it identify all arrays and the iterations over those arrays.
 * Then it looks for the filters over the elements of all those arrays. If it identifies
 * that there is filter based on a range of values, then it partitions the array into different
 * chuncks in the loading time. Then, it iterates only over the relavant partitions of the arrays
 * in the query processing time.
 *
 * TODO maybe add an example
 *
 * @param IR the polymorphic embedding trait which contains the reified program.
 * @param schema the schema information
 */
class ArrayPartitioning(override val IR: LoweringLegoBase, val schema: Schema) extends RuleBasedTransformer[LoweringLegoBase](IR) {
  import IR._

  import scala.collection.mutable

  val possibleRangeFors = mutable.Set[Rep[Unit]]()
  val rangeForIndex = mutable.Map[Rep[Unit], Rep[Int]]()
  val rangeArray = mutable.Map[Rep[Unit], Rep[Array[Any]]]()
  val rangeArrayApply = mutable.Map[Rep[Unit], Rep[Any]]()
  val rangeElemFields = mutable.Map[Rep[Unit], mutable.ArrayBuffer[Rep[Any]]]()
  val rangeElemFieldConstraints = mutable.Map[Rep[Unit], mutable.ArrayBuffer[Constraint]]()

  val arraysInfo = mutable.Set[ArrayInfo[Any]]()
  val arraysInfoConstraints = mutable.Map[ArrayInfo[Any], List[Constraint]]()
  val arraysInfoPartitioningField = mutable.Map[ArrayInfo[Any], String]()
  val arraysInfoLowerBound = mutable.Map[ArrayInfo[Any], Int]()
  val arraysInfoUpperBound = mutable.Map[ArrayInfo[Any], Int]()
  val arraysInfoBuckets = mutable.Map[ArrayInfo[Any], Int]()
  val arraysInfoArray = mutable.Map[ArrayInfo[Any], Rep[Array[Any]]]()
  val arraysInfoCount = mutable.Map[ArrayInfo[Any], Rep[Array[Int]]]()
  val arraysInfoElem = mutable.Map[ArrayInfo[Any], Rep[Any]]()

  sealed trait Phase
  case object CheckApplicablePhase extends Phase
  case object ConstraintCollectionPhase extends Phase

  var phase: Phase = _

  implicit def arrayInfoToArrayInfoAny[T](arrayInfo: ArrayInfo[T]): ArrayInfo[Any] = arrayInfo.asInstanceOf[ArrayInfo[Any]]

  sealed trait Constraint {
    val elemField: Rep[Any]
    def elemTpe: TypeRep[Any] = elemField match {
      case Def(StructImmutableField(elem, _)) => elem.tp
      case _                                  => ???
    }
    def bound: Rep[Any]
    def field: Option[String] = elemField match {
      case Def(StructImmutableField(_, f)) => Some(f)
      case _                               => None
    }
    def simplify: Constraint
    def isForDate: Boolean = field match {
      case Some(f) => f.endsWith("DATE")
      case None    => false
    }
  }

  def simplifyExpInt(exp: Rep[Int]): Rep[Int] = exp match {
    case Def(GenericEngineParseDateObject(Constant(d))) =>
      val data = d.split("-").map(x => x.toInt)
      unit((data(0) * 10000) + (data(1) * 100) + data(2))
    case _ => exp
  }

  sealed trait Predicate
  case object LE extends Predicate
  case object LEq extends Predicate
  case object GE extends Predicate
  case object GEq extends Predicate

  case class LessThan(elemField: Rep[Any], upperBound: Rep[Int]) extends Constraint {
    def bound = upperBound.asInstanceOf[Rep[Any]]
    def simplify = copy(upperBound = simplifyExpInt(this.upperBound))
  }
  case class GreaterThan(elemField: Rep[Any], lowerBound: Rep[Int]) extends Constraint {
    def bound = lowerBound.asInstanceOf[Rep[Any]]
    def simplify = copy(lowerBound = simplifyExpInt(this.lowerBound))
  }
  case class GreaterThanOffset(elemField: Rep[Any], lowerBound: Rep[Int], offset: Rep[Int]) extends Constraint {
    def bound = lowerBound.asInstanceOf[Rep[Any]]
    def simplify = {
      val newConst = copy(lowerBound = simplifyExpInt(this.lowerBound))
      (newConst.lowerBound, newConst.offset) match {
        // handles only the case for date
        case (Constant(a1), Constant(a2)) if isForDate => {
          val simpleDateFormatter = new java.text.SimpleDateFormat("yyyyMMdd")
          val date1 = simpleDateFormatter.parse(a1.toString).getTime
          val date2 = new java.util.Date(date1 + a2 * 1000L * 60 * 60 * 24)
          import java.util.Calendar
          val calendar = Calendar.getInstance()
          calendar.setTime(date2)
          val y2 = calendar.get(Calendar.YEAR)
          val m2 = calendar.get(Calendar.MONTH)
          val d2 = calendar.get(Calendar.DAY_OF_MONTH)
          val newNumber = y2 * 10000 + m2 * 100 + d2
          GreaterThan(newConst.elemField, Constant(newNumber))
        }
        case _ => newConst
      }
    }
  }

  object Comparison {
    def unapply[T](node: Def[T]): Option[(Rep[Int], Rep[Int], Predicate)] = node match {
      case Int$less1(a, b) =>
        Some(a, b, LE)
      case Int$less$eq1(a, b) =>
        Some(a, b, LEq)
      case Int$greater1(a, b) =>
        Some(a, b, GE)
      case Int$greater$eq1(a, b) =>
        Some(a, b, GEq)
      case _ =>
        None
    }
  }

  object ConstraintExtract {
    def unapply[T](node: Def[T]): Option[(Rep[Unit], Constraint)] = node match {
      case Comparison(elemField, bound, pred) if rangeElemFields.exists(_._2.contains(elemField)) =>
        val rangeForeach = rangeElemFields.find(_._2.contains(elemField)).get._1
        val constraint = pred match {
          case LE | LEq => LessThan(elemField, bound)
          case GE | GEq => GreaterThan(elemField, bound)
        }
        Some((rangeForeach, constraint))
      case _ =>
        None

    }
  }

  case class ArrayInfo[T](rangeForeachSymbol: Rep[Unit], arrayApplyIndex: Rep[Int], array: Rep[Array[T]]) {
    def tpe: TypeRep[T] = array.tp.typeArguments(0).asInstanceOf[TypeRep[T]]
    def constraints: List[Constraint] = arraysInfoConstraints.get(this).getOrElse(Nil)
    def field: String = //partitioningField(tpe).get
      arraysInfoPartitioningField(this)
    def fields: List[String] = constraints.flatMap(_.field)
    def lowerBound: Option[Int] = arraysInfoLowerBound.get(this)
    def upperBound: Option[Int] = arraysInfoUpperBound.get(this)
    def buckets: Rep[Int] = unit(arraysInfoBuckets(this))
    def arraySize: Rep[Int] = array match {
      case Def(ArrayNew(s)) => s
    }
    def count: Rep[Array[Int]] = arraysInfoCount(this)
    def partitionedArray: Rep[Array[Array[T]]] = arraysInfoArray(this).asInstanceOf[Rep[Array[Array[T]]]]
  }

  def getArrayInfo(rangeForeachSymbol: Rep[Unit]): Option[ArrayInfo[Any]] = {
    arraysInfo.find(_.rangeForeachSymbol == rangeForeachSymbol)
  }

  def shouldBePartitioned[T](arrayInfo: ArrayInfo[T]): Boolean = {
    // System.out.println(s"tablee: ${schema.tables.find(table => table.name + "Record" == arrayInfo.tpe.name)}")
    val polishedTableName = {
      val tpeName = arrayInfo.tpe.name
      val RECORD_POSTFIX = "Record"
      if (tpeName.endsWith(RECORD_POSTFIX))
        tpeName.dropRight(RECORD_POSTFIX.length)
      else
        tpeName
    }
    // System.out.println(s"shouldBePartitioned for $arrayInfo: ${polishedTableName}: ${schema.findTable(polishedTableName)} \n \t ${arrayInfo.constraints}")
    schema.findTable(polishedTableName) match {
      case Some(table) =>
        val constraints = rangeElemFieldConstraints.find(x => x._1 == arrayInfo.rangeForeachSymbol).map(_._2).getOrElse(Nil)
        // System.out.println(s"constraints: $constraints")
        constraints.flatMap(_.field).exists(field => table.findAttribute(field) match {
          case Some(attr) => attr.dataType == DateType
          case None       => false
        })
      // false
      case None => false
    }
  }

  def bucketSize[T](arrayInfo: ArrayInfo[T]): Rep[Int] = (arrayInfo.arraySize / arrayInfo.buckets) * unit(4)

  case class MyDate(year: Int, month: Int, day: Int) {
    def toInt: Int = year * 10000 + month * 100 + day
  }

  val MAX_DATE = MyDate(1999, 12, 30)
  val MIN_DATE = MyDate(1992, 1, 1)

  def convertDateToIndex(date: Rep[Int]): Rep[Int] = {
    val ym = date / unit(100)
    val month = ym % unit(100)
    val year = ym / unit(100)
    (year - unit(MIN_DATE.year)) * unit(12) + (month - unit(1))
  }

  def convertDateToIndex(date: Int): Int = {
    val ym = date / 100
    val month = ym % 100
    val year = ym / 100
    (year - MIN_DATE.year) * 12 + (month - 1)
  }

  def partitioningFunction[T](arrayInfo: ArrayInfo[T]): (Rep[Int] => Rep[Int]) = arrayInfo.constraints.head.isForDate match {
    case true => (x: Rep[Int]) => {
      convertDateToIndex(x)
    }
    case _ => ???
  }

  case class PredefinedConstraint(field1: String, field2: String, offset: Rep[Int])
  val predefinedConstraints = List(
    PredefinedConstraint("O_ORDERDATE", "L_SHIPDATE", unit(-122)))

  def computeConstraints(): Unit = {
    def applies1(const: Constraint): Option[PredefinedConstraint] = predefinedConstraints.find(_.field1 == const.field.get)
    def applies2(pred: PredefinedConstraint, const: Constraint): Boolean = pred.field2 == const.field.get
    // System.out.println(s"old: $rangeElemFieldConstraints")
    val filteredRangeElemConstraints = rangeElemFieldConstraints.filter(x => getArrayInfo(x._1).nonEmpty)
    for ((k1, s1) <- filteredRangeElemConstraints) {
      val arrayInfo1 = getArrayInfo(k1).get
      arraysInfoConstraints.getOrElseUpdate(arrayInfo1, s1.toList)
      for ((k2, s2) <- filteredRangeElemConstraints if k1 != k2) {
        val arrayInfo2 = getArrayInfo(k2).get
        for (c1 <- s1.distinct; c2 <- s2.distinct if applies1(c1).exists(pc => applies2(pc, c2))) {
          val newConst = (c1, c2) match {
            case (LessThan(e1, b1), GreaterThan(e2, b2)) if b1 == b2 => Some(GreaterThanOffset(e1, b1, applies1(c1).get.offset))
            case _ => None
          }
          // System.out.println(s"1: ${c1.elemTpe} 2: ${getArrayInfo(k1).get.tpe} -> $newConst")
          arraysInfoConstraints(arrayInfo1) = newConst.get :: arraysInfoConstraints(arrayInfo1)
        }
      }
      arraysInfoConstraints(arrayInfo1) = arraysInfoConstraints(arrayInfo1).map(_.simplify)
    }
    // System.out.println(arraysInfo.map(x => x -> x.constraints).mkString("\n"))
    for (arrayInfo <- arraysInfo) {
      if (arrayInfo.constraints.isEmpty) {
        // TODO do we need to do anything?
      } else if (arrayInfo.constraints.forall(c => c.isForDate)) {
        // Taking the constraints which are defining upperbound and lowerbound for a single symbol
        val filteredConstraints = for (x <- arrayInfo.constraints; y <- arrayInfo.constraints if x != y && x.elemField == y.elemField && x.field.nonEmpty) yield x
        arraysInfoConstraints += arrayInfo -> filteredConstraints
        // System.out.println(s"filteredConstraints: $filteredConstraints, not filtered: ${arrayInfo.constraints}")
        assert(filteredConstraints.size == 2 || filteredConstraints.size == 0)
        if (filteredConstraints.size == 2) {
          for (constraint <- filteredConstraints) {
            constraint match {
              case LessThan(_, Constant(upperBound))    => arraysInfoUpperBound += arrayInfo -> upperBound
              case GreaterThan(_, Constant(lowerBound)) => arraysInfoLowerBound += arrayInfo -> lowerBound
              case _                                    =>
            }
            arraysInfoPartitioningField += arrayInfo -> constraint.field.get
          }
          val buckets = convertDateToIndex(MAX_DATE.toInt) - convertDateToIndex(MIN_DATE.toInt) + 1
          // System.out.println(s"added bucket for: $arrayInfo")
          arraysInfoBuckets += arrayInfo -> buckets
        } else {
          // we should not consider those arrayInfos
          arraysInfo.remove(arrayInfo)
          arraysInfoConstraints.remove(arrayInfo)
        }
      }
    }
  }

  override def postAnalyseProgram[T: TypeRep](node: Block[T]): Unit = {
    arraysInfo ++= possibleRangeFors.filter(rf => rangeForIndex.contains(rf) && rangeArray.contains(rf)).map(rf =>
      ArrayInfo(rf, rangeForIndex(rf), rangeArray(rf))).filter(shouldBePartitioned)
    computeConstraints()
  }

  override def analyseProgram[T: TypeRep](node: Block[T]): Unit = {
    phase = CheckApplicablePhase
    traverseBlock(node)
    phase = ConstraintCollectionPhase
    // traverseBlock(node)
  }

  analysis += statement {
    case sym -> RangeForeach(Def(RangeApplyObject(start, end)), Def(Lambda(_, i, body))) if phase == CheckApplicablePhase => {
      val unitSym = sym.asInstanceOf[Rep[Unit]]
      possibleRangeFors += unitSym
      rangeForIndex += unitSym -> i.asInstanceOf[Rep[Int]]
      traverseBlock(body)
      ()
    }
  }

  analysis += statement {
    case sym -> ArrayApply(arr, index) if phase == CheckApplicablePhase && rangeForIndex.exists(_._2 == index) => {
      val rangeForeach = rangeForIndex.find(_._2 == index).get._1
      rangeArray += rangeForeach -> arr
      rangeArrayApply += rangeForeach -> sym
      ()
    }
  }

  analysis += statement {
    case sym -> StructImmutableField(elem, field) if phase == CheckApplicablePhase && (rangeArrayApply.exists(_._2 == elem)) =>
      val rangeForeach = rangeArrayApply.find(_._2 == elem).get._1
      rangeElemFields.getOrElseUpdate(rangeForeach, mutable.ArrayBuffer()) += sym
      ()
  }

  analysis += statement {
    case sym -> ConstraintExtract(rangeForeach, constraint) if phase == CheckApplicablePhase =>
      rangeElemFieldConstraints.getOrElseUpdate(rangeForeach, mutable.ArrayBuffer()) += constraint
      ()
  }

  def array_foreach[T: TypeRep](arr: Rep[Array[T]], f: Rep[T] => Rep[Unit]): Rep[Unit] = {
    Range(unit(0), arr.length).foreach {
      __lambda { i =>
        val e = arr(i)
        f(e)
      }
    }
  }

  def createPartitionArray[InnerType: TypeRep](arrayInfo: ArrayInfo[InnerType]): Unit = {
    val buckets = arrayInfo.buckets
    val partitionedArray = __newArray[Array[InnerType]](buckets)
    val partitionedCount = __newArray[Int](buckets)
    val originalArray = arrayInfo.array
    arraysInfoArray(arrayInfo) = partitionedArray.asInstanceOf[Rep[Array[Any]]]
    arraysInfoCount(arrayInfo) = partitionedCount
    Range(unit(0), buckets).foreach {
      __lambda { i =>
        partitionedArray(i) = __newArray[InnerType](bucketSize(arrayInfo))
      }
    }
    val index = __newVarNamed[Int](unit(0), "partIndex")
    array_foreach(originalArray, {
      (e: Rep[InnerType]) =>
        val pkey = partitioningFunction(arrayInfo)(field[Int](e, arrayInfo.field))
        val currIndex = partitionedCount(pkey)
        val partitionedArrayBucket = partitionedArray(pkey)
        partitionedArrayBucket(currIndex) = e
        partitionedCount(pkey) = currIndex + unit(1)
        __assign(index, readVar(index) + unit(1))
    })
  }

  rewrite += rule {
    case GenericEngineRunQueryObject(b) =>
      for (arrayInfo <- arraysInfo) {
        createPartitionArray(arrayInfo)(arrayInfo.tpe)
      }
      val newBlock = transformBlock(b)(b.tp)
      GenericEngineRunQueryObject(newBlock)(newBlock.tp)
  }

  var filling = false

  rewrite += statement {
    case sym -> RangeForeach(range, func) if arraysInfo.exists(_.rangeForeachSymbol == sym) => {
      class ElemType
      val arrayInfo = arraysInfo.find(_.rangeForeachSymbol == sym).get.asInstanceOf[ArrayInfo[ElemType]]

      implicit val elemType = arrayInfo.tpe.asInstanceOf[TypeRep[ElemType]]
      Range(unit(convertDateToIndex(arrayInfo.lowerBound.get)), unit(convertDateToIndex(arrayInfo.upperBound.get) + 1)).foreach {
        __lambda { bucketIndex =>
          val size = arrayInfo.count(bucketIndex)
          val bucketArray = arrayInfo.partitionedArray(bucketIndex)
          Range(unit(0), size).foreach {
            __lambda { index =>
              val elem = bucketArray(index)
              arraysInfoElem(arrayInfo) = elem
              filling = true
              inlineFunction(func, arrayInfo.arrayApplyIndex)
              filling = false
              unit(())
            }
          }
        }

      }
    }
  }

  rewrite += statement {
    case sym -> ArrayApply(arr, index) if filling && arraysInfo.exists(ai => ai.array == arr && ai.arrayApplyIndex == index) => {
      val arrayInfo = arraysInfo.find(ai => ai.array == arr && ai.arrayApplyIndex == index).get
      arraysInfoElem(arrayInfo)
    }
  }
}
