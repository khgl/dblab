package ch.epfl.data
package dblab.legobase
package tpch

import schema._
import utils.Utilities._
import java.io.PrintStream

/**
 * The common trait for all TPCH Query Runners (either a TPCH Query Interpreter or a TPCH Query Compiler)
 */
trait TPCHRunner extends LegoRunner {
  Config.checkResults = true

  /** Specifies the scaling factor for TPCH queries */
  var scalingFactor: Double = _

  def getOutputName(query: String): String = query + "Output.txt"

  def getResultFileName(query: String): String =
    "results/" + query + ".result_sf" + scalingFactor

  def getQueries(args: Array[String]): List[String] = {
    val excludedQueries = Nil
    if (args.length >= 3 && args(2) == "testsuite-scala")
      (for (i <- 1 to 22 if !excludedQueries.contains(i)) yield "Q" + i).toList
    else if (args.length >= 3 && args(2) == "testsuite-c")
      (for (i <- 1 to 22 if !excludedQueries.contains(i)) yield "Q" + i + "_C").toList
    else
      args.drop(2).filter(x => !x.startsWith("+") && !x.startsWith("-")).toList
  }

  def getSchema(args: Array[String]): Schema = {
    TPCHSchema.getSchema(Config.datapath, scalingFactor)
  }

  def preprocessArgs(args: Array[String]): Unit = {
    val sf = if (args(1).contains(".")) args(1).toDouble.toString else args(1).toInt.toString
    scalingFactor = sf.toDouble
    Config.datapath = args(0) + "/sf" + sf + "/"
  }
}
