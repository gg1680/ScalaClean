package scalaclean.deadcode

import java.nio.charset.StandardCharsets

import org.scalatest.exceptions.TestFailedException
import scalafix.internal.patch.PatchInternals
import scalafix.internal.testkit.{AssertDiff, CommentAssertion}
import scalafix.lint.RuleDiagnostic
import scalafix.testkit.{RuleTest, SemanticRuleSuite}
import scalafix.v1.{Rule, SemanticDocument, SemanticRule}

import scala.meta._
import scala.meta.internal.io.FileIO

class RuleSuite extends SemanticRuleSuite() {
  // The logic here looks for all files in the input directory and runs them as tests.
  // the config over which rules to run is defined at the top of the file.

  override def runAllTests() = {
    testsToRun.filter(_.path.input.toString().contains("scalaclean")).foreach(runOn)
  }

  runAllTests()

  def semanticPatch(
    rule: Rule,
    sdoc: SemanticDocument,
    suppress: Boolean
  ): (String, List[RuleDiagnostic]) = {
    val fixes = (rule match {
      case rule: SemanticRule =>
        Some(rule.name -> rule.fix(sdoc))
      case _ =>
        None
    }).map(Map.empty + _).getOrElse(Map.empty)
    PatchInternals.semantic(fixes, sdoc, suppress)
  }

  // Overridden version of runOn which is hacked ro to run the rules in sequence rather than parallised.
  // earlier rules results are ignored - i.e. this only works for ScalaCleanDeadCodeAnalysis followed by a proper rule for
  // testing.
  override def runOn(diffTest: RuleTest): Unit = {
    test(diffTest.path.testName) {
      val (rule, sdoc) = diffTest.run.apply()

      var fixed: String = ""
      var messages: List[RuleDiagnostic] = Nil
      rule.rules.foreach { r =>
        r.beforeStart()
        val res = semanticPatch(r, sdoc, suppress = false)
        fixed = res._1
        messages = res._2
        r.afterComplete()
      }

      val tokens = fixed.tokenize.get
      val obtained = SemanticRuleSuite.stripTestkitComments(tokens)
      val expected = diffTest.path.resolveOutput(props) match {
        case Right(file) =>
          FileIO.slurp(file, StandardCharsets.UTF_8)
        case Left(err) =>
          if (fixed == sdoc.input.text) {
            // rule is a linter, no need for an output file.
            obtained
          } else {
            fail(err)
          }
      }

      val expectedLintMessages = CommentAssertion.extract(sdoc.tokens)
      val diff = AssertDiff(messages, expectedLintMessages)

      if (diff.isFailure) {
        println("###########> Lint       <###########")
        println(diff.toString)
      }

      val result = compareContents(obtained, expected)
      if (result.nonEmpty) {
        println("###########> Diff       <###########")
        println(error2message(obtained, expected))
      }

      if (result.nonEmpty || diff.isFailure) {
        throw new TestFailedException("see above", 0)
      }
    }
  }
}
