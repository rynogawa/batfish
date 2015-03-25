package org.batfish.grammar.flatjuniper;

import java.util.ArrayList;
import java.util.List;

import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.batfish.grammar.flatjuniper.FlatJuniperParser.*;
import org.batfish.grammar.flatjuniper.Hierarchy.HierarchyTree;
import org.batfish.grammar.flatjuniper.Hierarchy.HierarchyTree.HierarchyPath;
import org.batfish.main.BatfishException;
import org.batfish.main.Warnings;

public class ApplyGroupsApplicator extends FlatJuniperParserBaseListener {

   private final FlatJuniperCombinedParser _combinedParser;

   private Flat_juniper_configurationContext _configurationContext;

   private HierarchyPath _currentPath;

   private Set_lineContext _currentSetLine;

   private boolean _enablePathRecording;

   private final Hierarchy _hierarchy;

   private List<ParseTree> _newConfigurationLines;

   private boolean _reenablePathRecording;

   private final Warnings _w;

   public ApplyGroupsApplicator(FlatJuniperCombinedParser combinedParser,
         Hierarchy hierarchy, Warnings warnings) {
      _combinedParser = combinedParser;
      _hierarchy = hierarchy;
      _w = warnings;
   }

   @Override
   public void enterFlat_juniper_configuration(
         Flat_juniper_configurationContext ctx) {
      _configurationContext = ctx;
      _newConfigurationLines = new ArrayList<ParseTree>();
      _newConfigurationLines.addAll(ctx.children);
   }

   @Override
   public void enterInterface_id(Interface_idContext ctx) {
      if (_enablePathRecording && ctx.unit != null) {
         _enablePathRecording = false;
         _reenablePathRecording = true;
         String text = ctx.getText();
         _currentPath.addNode(text);
      }
   }

   @Override
   public void enterS_apply_groups(S_apply_groupsContext ctx) {
      String groupName = ctx.name.getText();
      try {
         List<ParseTree> applyGroupsLines = _hierarchy.getApplyGroupsLines(
               groupName, _currentPath, _configurationContext);
         int insertionIndex = _newConfigurationLines.indexOf(_currentSetLine);
         _newConfigurationLines.remove(_currentSetLine);
         _newConfigurationLines.addAll(insertionIndex, applyGroupsLines);
      }
      catch (BatfishException e) {
         String message = "Exception processing apply-groups statement at path: \""
               + _currentPath.pathString()
               + "\" with group \""
               + groupName
               + "\": "
               + e.getMessage()
               + ": caused by: "
               + ExceptionUtils.getFullStackTrace(e);
         _w.redFlag(message);
      }
   }

   @Override
   public void enterS_apply_groups_except(S_apply_groups_exceptContext ctx) {
      _newConfigurationLines.remove(_currentSetLine);
   }

   @Override
   public void enterSet_line(Set_lineContext ctx) {
      _currentSetLine = ctx;
   }

   @Override
   public void enterSet_line_tail(Set_line_tailContext ctx) {
      _enablePathRecording = true;
      _currentPath = new HierarchyPath();
   }

   @Override
   public void exitFlat_juniper_configuration(
         Flat_juniper_configurationContext ctx) {
      _configurationContext.children = _newConfigurationLines;
   }

   @Override
   public void exitInterface_id(Interface_idContext ctx) {
      if (_reenablePathRecording) {
         _enablePathRecording = true;
         _reenablePathRecording = false;
      }
   }

   @Override
   public void exitS_groups_named(S_groups_namedContext ctx) {
      String groupName = ctx.name.getText();
      HierarchyTree tree = _hierarchy.getTree(groupName);
      if (tree == null) {
         tree = _hierarchy.newTree(groupName);
      }
      StatementContext statement = ctx.s_groups_tail().statement();
      if (statement == null) {
         return;
      }
      Interval interval = ctx.s_groups_tail().getSourceInterval();
      List<Token> unfilteredTokens = _combinedParser.getTokens().getTokens(
            interval.a, interval.b);
      HierarchyPath path = new HierarchyPath();
      for (Token currentToken : unfilteredTokens) {
         if (currentToken.getChannel() != Lexer.HIDDEN) {
            String text = currentToken.getText();
            if (currentToken.getType() == FlatJuniperLexer.WILDCARD) {
               path.addWildcardNode(text);
            }
            else {
               path.addNode(text);
            }
         }
      }
      path.setStatement(statement);
      tree.addPath(path, _currentSetLine, null);
   }

   @Override
   public void exitSet_line(Set_lineContext ctx) {
      _currentSetLine = null;
      _currentPath = null;
   }

   @Override
   public void exitSet_line_tail(Set_line_tailContext ctx) {
      _enablePathRecording = false;
   }

   @Override
   public void visitTerminal(TerminalNode node) {
      if (_enablePathRecording) {
         String text = node.getText();
         if (node.getSymbol().getType() == FlatJuniperLexer.WILDCARD) {
            _currentPath.addWildcardNode(text);
         }
         else {
            _currentPath.addNode(text);
         }
      }
   }

}
