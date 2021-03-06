// Copyright 2013, Martin Pokorny <martin@truffulatree.org>
//
// This Source Code Form is subject to the terms of the Mozilla Public License,
// v. 2.0. If a copy of the MPL was not distributed with this file, You can
// obtain one at http://mozilla.org/MPL/2.0/.
//
package org.truffulatree.h2odb

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.util.Try
import com.healthmarketscience.jackcess.{Database, Table, CursorBuilder}
import org.slf4j.LoggerFactory
import org.apache.poi.hssf.usermodel.HSSFWorkbook

object DBFiller {
  private val logger = LoggerFactory.getLogger(getClass.getName.init)

  private val samplePointIdXls = "SamplePointID"

  /** Type of records from XLS format file of water analysis results
    */
  type XlsRecord = Map[String,String]

  /** Type of record that is recorded in the target database
    */
  type DbRecord = Map[String,Any]

  implicit object DbRecordOrdering extends Ordering[DbRecord] {
    def compare(rec0: DbRecord, rec1: DbRecord): Int = {
      (rec0(Tables.DbTableInfo.samplePointId),
        rec1(Tables.DbTableInfo.samplePointId)) match {
        case (id0: String, id1: String) => (id0 compare id1) match {
          case 0 => {
            (rec0(Tables.DbTableInfo.analyte),
              rec1(Tables.DbTableInfo.analyte)) match {
              case (a0: String, a1: String) => a0 compare a1
            }
          }
          case cmp => cmp
        }
      }
    }
  }

  /** Process a xls file of water analysis records, and insert the processed
    * records into a database.
    *
    * The processing steps are as follows:
    *
    *  1. Read in all lines of xls file.
    *  1. Check that header line from xls file has the expected column
    *     names.
    *  1. Create a sequence corresponding to the rows in the xls file of maps
    *     from column title to column value.
    *  1. Check that the "Param" value in each element of the sequence (i.e, an
    *     xls row) is an expected value.
    *  1. Check that the "Test" values, for those "Param"s that have tests, are
    *     expected values.
    *  1. Remove sequence elements with sample point IDs that do not exist in
    *     the database "Chemistry SampleInfo" table.
    *  1. Create map from sample point IDs to sample numbers.
    *  1. Check that there is exactly one "SampleNumber" value associated with
    *     each "SamplePointID" value.
    *  1. Check that sample point IDs in remaining sequence elements do _not_
    *     exist in major and minor chemistry database tables.
    *  1. Convert the sequence of maps derived from the xls into a new sequence
    *     of maps compatible with the database table schemas.
    *  1. Remove "low priority" test results (this ensures that only the most
    *     preferred test results for those rows with "Test" values get into the
    *     database).
    *  1. Add new rows to the database.
    *  1. Add sample lab ids to the database.
    *  1. Scan sequence of maps that were just inserted into the database to
    *     find those records that fail to meet drinking water standards, and
    *     print out a message for those that fail.
    *
    * @param writeln  write a string to output (with added newline)
    * @param xls      HSSFWorkbook from water analysis report in XLS format
    * @param db       Database for target database
    */
  def apply(writeln: (String) => Unit, xls: HSSFWorkbook, db: Database): Unit = {
    // read rows from xls file
    val lines = getXlsRows(xls)
    // extract header (column names)
    val header = lines(0)
    // check that header fields have only what is expected
    validateHeaderFields(header).get
    // create a sequence of maps (column name -> cell value) from cvs rows
    val records = lines.tail map { fields =>
      (header zip fields).toMap
    }
    // check for known "Param" field values
    validateParams(records).get
    // check for known "Test" field values
    validateTests(records).get
    // filter for sample point id in db
    val knownPoints =
      (Set.empty[String] /:
        db.getTable(Tables.DbTableInfo.ChemistrySampleInfo.name)) {
        case (points, row) =>
          points + row.get(Tables.DbTableInfo.samplePointId).toString
      }
    val recordsInDb =
      records filter (r => knownPoints.contains(r(samplePointIdXls)))
    // collect sample numbers
    val sampleNumbers = collectSampleNumbers(recordsInDb)
    // check for unique "SampleNumber" value for each "SamplePointID" value
    validateSampleNumbers(sampleNumbers).get
    // get major chemistry table from database
    val majorChemistry = db.getTable(Tables.DbTableInfo.MajorChemistry.name)
    // get minor chemistry table from database
    val minorChemistry = db.getTable(Tables.DbTableInfo.MinorChemistry.name)
    // get chemistry sample info table from database
    val chemSampleInfo = db.getTable(Tables.DbTableInfo.ChemistrySampleInfo.name)
    // convert records to db schema compatible format
    val convertedRecords = recordsInDb map (
      r => convertXLSRecord(chemSampleInfo, majorChemistry, minorChemistry, r))
    // check that samples don't already exist in major and minor chem tables
    validateSamples(
      convertedRecords,
      List(majorChemistry, minorChemistry)).get
    // filter out records for low priority tests
    val newRecords = removeLowPriorityRecords(convertedRecords)
    if (!newRecords.isEmpty) {
      if (logger.isDebugEnabled)
        newRecords foreach { rec => logger.debug((rec - "Table").toString) }
      // add rows to database
      addChemTableRows(newRecords)
      insertLabIds(sampleNumbers, db)
      db.flush()
      // report on added records
      val sortedRecords = newRecords.sorted
      writeln(
        s"Added ${newRecords.length} records with the following sample point IDs to database:")
      (Set.empty[String] /: sortedRecords) {
        case (acc, rec) => acc + rec(Tables.DbTableInfo.samplePointId).toString
      } foreach { id =>
        writeln(id)
      }
      writeln("----------")
      // test values against water quality standards
      checkStandards(writeln, sortedRecords)
    } else {
      writeln("Added 0 rows to database")
    }
  }

  /** Validate header fields
    *
    * Compare header field names to list of expected names.
    *
    * @param header  Seq of header field names to validate
    * @return        Unit or an Exception
    */
  private def validateHeaderFields(header: Seq[String]): Try[Unit] =
    Try {
      if (!header.contains(samplePointIdXls))
        throw new InvalidInputHeader(
          s"XLS file is missing '$samplePointIdXls' column")
    }

  /** Validate param fields for all records
    *
    * Compare "Param" field values to list of expected values.
    *
    * @param records  Seq of [[XlsRecord]]s to validate
    * @return         Unit or an Exception
    */
  private def validateParams(records: Seq[XlsRecord]): Try[Unit] =
    Try {
      val missing = (Set.empty[String] /: records) {
        case (miss, rec) =>
          if (!Tables.analytes.contains(rec("Param"))) miss + rec("Param")
          else miss
      }
      if (!missing.isEmpty)
        throw new MissingParamConversion(
          ("""|The following 'Param' values in the spreadsheet have no known
              |conversion to an analyte code:
              |\n""" + missing.mkString("\n")).stripMargin)
    }

  /** Validate test descriptions for all records
    *
    * Compare "Test" field values to list of expected values
    *
    * @param records  Seq of [[XlsRecord]]s to validate
    * @return         Unit or an Exception
    */
  private def validateTests(records: Seq[XlsRecord]): Try[Unit] =
    Try {
      def isValidTest(rec: Map[String,String]) = {
        val param = rec("Param")
        !Tables.testPriority.contains(param) ||
        Tables.testPriority(param).contains(rec("Test"))
      }
      val invalidTests = records filter (!isValidTest(_))
      if (!invalidTests.isEmpty) {
        val invalid = invalidTests map (
          r => (r("SamplePointID"),r("Param"),r("Test")))
        throw new InvalidTestDescription(
          s"Invalid test descriptions for\n${invalid.mkString("\n")}")
      }
    }

  /** Collect "SampleNumber" values
    * 
    * @param records  Seq of [[XlsRecord]]s
    * @return         Map from sample point id to set of sample numbers
    */
  private def collectSampleNumbers(records: Seq[XlsRecord]):
      Map[String,Set[String]] = {
    val sampleNumbers =
      ((Map.empty[String,mutable.Set[String]]) /: records) {
        case (acc, rec) => {
          val sp = rec(samplePointIdXls)
          if (acc.contains(sp)) {
            acc(sp) += rec("SampleNumber")
            acc
          } else {
            acc + ((sp, mutable.Set(rec("SampleNumber"))))
          }
        }
      }
    sampleNumbers.mapValues(_.toSet)
  }

  /** Validate sample numbers for all records
    *
    * Check that every "SamplePointID" is associated with exactly one
    * "SampleNumber".
    *
    * @param sampleNumbers  Map of sample point ids to set of sample numbers
    * @return               Unit or an Exception
    */
  private def validateSampleNumbers(sampleNumbers: Map[String,Set[String]]): Try[Unit] =
    Try {
      val nonUnique = sampleNumbers.filter {
        case (_, sns) => sns.size != 1
      }
      if (nonUnique.size > 0)
        throw new NonUniqueSampleNumber(
          "Sample numbers for each of the following sample point ids are variable\n" +
            s"${nonUnique.keys.mkString("\n")}")
    }

  /** Validate samples by checking whether sample point IDs already exist in given
    * database tables for analytes expected in analysis reports.
    *
    * @param records  Seq of [[DbRecord]]s to validate
    * @param tables   Seq of tables to check for existing sample point IDs
    * @return         Unit or an Exception
    */
  private def validateSamples(
    records: Seq[DbRecord],
    tables: Seq[Table]): Try[Unit] = {
    def getSamples(t: Table): Set[(String,String)] =
      (Set.empty[(String,String)] /: t) {
        case (acc, row) =>
          val analyte = row.get(Tables.DbTableInfo.analyte)
          if (analyte != null)
            acc + ((row.get(Tables.DbTableInfo.samplePointId).toString,
              analyte.toString))
          else
            acc
      }
    val existingSamples = tables map (getSamples _) reduceLeft (_ ++ _)
    val invalidRecords = records filter { r =>
      existingSamples.contains(
        (r(Tables.DbTableInfo.samplePointId).toString,
          r(Tables.DbTableInfo.analyte).toString))
    }
    Try {
      if (!invalidRecords.isEmpty) {
        val invalidSamplePointIds = (Set.empty[String] /: invalidRecords) {
          case (acc, rec) => acc + rec(Tables.DbTableInfo.samplePointId).toString
        }
        throw new DuplicateSample(
          "Database already contains gen chem data for the following sample points\n" +
            s"${invalidSamplePointIds.mkString("\n")}")
      }
    }
  }

  /** Convert xls records to database table format
    *
    * Convert a (single) [[XlsRecord]] into a [[DbRecord]]. The resulting
    * [[DbRecord]] is ready for addition to the appropriate database table.
    *
    * @param major   "Major chemistry" database table
    * @param minor   "Minor chemistry" database table
    * @param record  [[XlsRecord]] to convert
    * @return        [[DbRecord]] derived from record
    */
  private def convertXLSRecord(
    info: Table,
    major: Table,
    minor: Table,
    record: XlsRecord): DbRecord = {
    import Tables.DbTableInfo._

    val result: mutable.Map[String,Any] = mutable.Map()

    record foreach {

      // "ND" result value
      case ("ReportedND", "ND") => {
        // set value to lower limit (as Float)
        result(sampleValue) =
          record("LowerLimit").toFloat * record("Dilution").toFloat
        // add "symbol" column value (as String)
        result(symbol) = "<"
      }

      // normal result value
      case ("ReportedND", v) =>
        result(sampleValue) = v.toFloat // as Float

      // sample point id
      case ("SamplePointID", id) => {
        // set sample point id (as String)
        result(samplePointId) = id
        // set point id (as String)
        result(pointId) = id.init
        // set sample point guid (as String)
        result(samplePointGUID) =
          (info withFilter { r =>
            r(samplePointId) == id
          } map { r =>
            r(samplePointGUID)
          }).head.toString
      }

      // water parameter identification
      case ("Param", p) => {
        // analyte code (name)
        result(analyte) = Tables.analytes(p) // as String
        // "AnalysisMethod", if required
        if (Tables.method.contains(p))
          result(analysisMethod) = Tables.method(p) // as String
        // record table this result goes into (as table reference)
        result("Table") = Tables.chemistryTable(p) match {
          case MajorChemistry.name => major
          case MinorChemistry.name => minor
        }
        // set test result priority value (as Int)
        result("Priority") =
          if (Tables.testPriority.contains(p))
            Tables.testPriority(p).indexOf(record("Test"))
          else
            0
      }

      // test result units (as String); some are converted, some not
      case ("Results_Units", u) =>
        result(units) = Tables.units.getOrElse(record("Param"), u)

      // drop any other column
      case _ =>
    }
    result.toMap
  }

  /** Remove records from low priority test results
    *
    * For each value of the pair (sample point, analyte) retain only the record
    * with the most preferred test method.
    *
    * @param records  Seq of [[DbRecord]]s
    * @return         Seq of [[DbRecord]]s with only the highest priority test
    *                 results remaining
    */
  private def removeLowPriorityRecords(records: Seq[DbRecord]): Seq[DbRecord] =
    ((Map.empty[(String,String),DbRecord] /: records) {
      case (newrecs, rec) => {
        val key = (rec(Tables.DbTableInfo.samplePointId).asInstanceOf[String],
          rec(Tables.DbTableInfo.analyte).asInstanceOf[String])
        if (!newrecs.contains(key) ||
          (rec("Priority").asInstanceOf[Int] <
            newrecs(key)("Priority").asInstanceOf[Int]))
          newrecs + ((key, rec))
        else newrecs
      }
    }).values.toSeq

  /** Compare analyte test result to water quality standards
    *
    * @param record  [[DbRecord]] to compare to standards
    * @return        true, if test result falls within limits;
    *                false, otherwise
    */
  private def meetsStandards(record: DbRecord): Boolean = {
    (Tables.standards.get(record(Tables.DbTableInfo.analyte).toString) map {
      case (lo, hi) => {
        record(Tables.DbTableInfo.sampleValue) match {
          case v: Float => lo <= v && v <= hi
        }
      }
    }).getOrElse(true)
  }

  /** Add records to chemistry database tables
    *
    * Add each record to the appropriate chemistry database table
    *
    * @param records  Seq of [[DbRecord]]s to add to database
    */
  private def addChemTableRows(records: Seq[DbRecord]) {
    val tables = Set((records map (_.apply("Table").asInstanceOf[Table])):_*)
    val colNames = Map(
      (tables.toSeq map { tab => (tab, tab.getColumns.map(_.getName)) }):_*)
    records foreach { rec =>
      val table = rec("Table").asInstanceOf[Table]
      val row = colNames(table) map { col =>
        rec.getOrElse(col, null).asInstanceOf[Object] }
      if (logger.isDebugEnabled) logger.debug(s"$row -> ${table.getName})")
      table.addRow(row:_*)
    }
  }

  /** Add sample lab ids to chemistry sample info database table
    *
    * @param sampleNumbers  Map of sample ids to set of sample numbers
    *                       (sets are assumed to have one element each)
    */
  private def insertLabIds(sampleNumbers: Map[String,Set[String]], db: Database):
      Unit = {
    import Tables.DbTableInfo._
    val sampleInfoTable = db.getTable(ChemistrySampleInfo.name)
    val cursor = CursorBuilder.createCursor(sampleInfoTable)
    sampleNumbers foreach {
      case (sId, sNumSet) =>
        cursor.findFirstRow(Map(samplePointId -> sId))
        if (logger.isDebugEnabled)
          logger.debug(s"${sId}.${labId} <- ${sNumSet.head}")
        val colUpdate = new java.util.HashMap[String,Object]
        colUpdate(labId) = sNumSet.head
        cursor.updateCurrentRowFromMap(colUpdate)
    }
  }

  /** Check and report on analyte test results comparison to standards
    *
    * @param writeln   function to output a line a text
    * @param records   Seq of [[DbRecord]]s to check
    */
  private def checkStandards(writeln: (String) => Unit, records: Seq[DbRecord]):
      Unit = {
    import Tables.DbTableInfo.{samplePointId, analyte, sampleValue, units}
    val poorQuality = records filter (!meetsStandards(_))
    if (!poorQuality.isEmpty) {
      val failStr =
        if (poorQuality.length > 1)
          s"${poorQuality.length} records fail"
        else
          "1 record fails"
      writeln(failStr + " to meet water standards:")
      poorQuality foreach { rec =>
        (rec(samplePointId), rec(analyte), rec(sampleValue), rec(units)) match {
          case (s: String, a: String, v: Float, u: String) =>
            writeln(f"$s - $a ($v%g $u)")
        }
      }
    } else writeln("All records meet all water standards")
  }

  /** Get data rows from XLS file.
    * 
    * Cell values are converted to strings.
    * 
    * @param xls  HSSFWorkbook for XLS input file
    */
  private def getXlsRows(xls: HSSFWorkbook): Seq[Seq[String]] = {
    val sheet = xls.getSheetAt(0)
    (sheet.getFirstRowNum to sheet.getLastRowNum) map { r =>
      sheet.getRow(r)
    } withFilter { row =>
      row != null
    } map { row =>
      (row.getFirstCellNum until row.getLastCellNum) map { c =>
        val cell = row.getCell(c)
        if (cell != null) cell.toString else ""
      }
    }
  }

}
