package io.routify.gateway.filter.validation;

import graphql.language.*;
import graphql.parser.Parser;
import graphql.parser.InvalidSyntaxException;

import java.util.*;

/**
 * Analyzes a GraphQL query AST to compute depth, complexity, and alias counts.
 *
 * <p>Used by {@link GraphQLDepthLimitGatewayFilterFactory} to enforce per-route
 * query complexity limits. Only parses the AST — no execution engine is invoked.
 *
 * <p>The parser is lazy-initialized on first use to avoid startup latency.
 */
public final class GraphQLQueryAnalyzer {

    private GraphQLQueryAnalyzer() {
        // utility class
    }

    /** Result of analyzing a GraphQL query. */
    public record AnalysisResult(int depth, int complexity, int aliasCount, boolean hasIntrospection) {}

    /**
     * Parses and analyzes a GraphQL query string.
     *
     * @param query the GraphQL query string
     * @return analysis result with depth, complexity, alias count, and introspection flag
     * @throws InvalidSyntaxException if the query cannot be parsed
     */
    public static AnalysisResult analyze(String query) {
        Document document = Parser.parse(query);
        return analyzeDocument(document);
    }

    /**
     * Analyzes a pre-parsed GraphQL document.
     */
    static AnalysisResult analyzeDocument(Document document) {
        int maxDepth = 0;
        int totalComplexity = 0;
        int totalAliases = 0;
        boolean hasIntrospection = false;

        // Collect fragment definitions for resolving fragment spreads
        Map<String, FragmentDefinition> fragments = new HashMap<>();
        for (Definition<?> def : document.getDefinitions()) {
            if (def instanceof FragmentDefinition frag) {
                fragments.put(frag.getName(), frag);
            }
        }

        for (Definition<?> def : document.getDefinitions()) {
            if (def instanceof OperationDefinition op) {
                SelectionSet selectionSet = op.getSelectionSet();
                if (selectionSet != null) {
                    FieldStats stats = analyzeSelectionSet(selectionSet, fragments, new HashSet<>(), 1);
                    maxDepth = Math.max(maxDepth, stats.maxDepth);
                    totalComplexity += stats.complexity;
                    totalAliases += stats.aliasCount;
                    if (stats.hasIntrospection) hasIntrospection = true;
                }
            }
        }

        return new AnalysisResult(maxDepth, totalComplexity, totalAliases, hasIntrospection);
    }

    // ─── AST traversal ────────────────────────────────────────────────────────

    private record FieldStats(int maxDepth, int complexity, int aliasCount, boolean hasIntrospection) {}

    private static FieldStats analyzeSelectionSet(SelectionSet selectionSet,
                                                   Map<String, FragmentDefinition> fragments,
                                                   Set<String> visitedFragments,
                                                   int currentDepth) {
        int maxDepth = currentDepth;
        int complexity = 0;
        int aliasCount = 0;
        boolean hasIntrospection = false;

        for (Selection<?> selection : selectionSet.getSelections()) {
            if (selection instanceof Field field) {
                complexity++;

                // Check for aliases
                if (field.getAlias() != null) {
                    aliasCount++;
                }

                // Check for introspection fields
                String fieldName = field.getName();
                if ("__schema".equals(fieldName) || "__type".equals(fieldName)) {
                    hasIntrospection = true;
                }

                // Recurse into sub-selections
                if (field.getSelectionSet() != null) {
                    FieldStats childStats = analyzeSelectionSet(
                            field.getSelectionSet(), fragments, visitedFragments, currentDepth + 1);
                    maxDepth = Math.max(maxDepth, childStats.maxDepth);
                    complexity += childStats.complexity;
                    aliasCount += childStats.aliasCount;
                    if (childStats.hasIntrospection) hasIntrospection = true;
                }
            } else if (selection instanceof InlineFragment inlineFragment) {
                if (inlineFragment.getSelectionSet() != null) {
                    FieldStats childStats = analyzeSelectionSet(
                            inlineFragment.getSelectionSet(), fragments, visitedFragments, currentDepth);
                    maxDepth = Math.max(maxDepth, childStats.maxDepth);
                    complexity += childStats.complexity;
                    aliasCount += childStats.aliasCount;
                    if (childStats.hasIntrospection) hasIntrospection = true;
                }
            } else if (selection instanceof FragmentSpread spread) {
                String fragName = spread.getName();
                // Guard against circular fragment references
                if (!visitedFragments.contains(fragName)) {
                    visitedFragments.add(fragName);
                    FragmentDefinition fragDef = fragments.get(fragName);
                    if (fragDef != null && fragDef.getSelectionSet() != null) {
                        FieldStats childStats = analyzeSelectionSet(
                                fragDef.getSelectionSet(), fragments, visitedFragments, currentDepth);
                        maxDepth = Math.max(maxDepth, childStats.maxDepth);
                        complexity += childStats.complexity;
                        aliasCount += childStats.aliasCount;
                        if (childStats.hasIntrospection) hasIntrospection = true;
                    }
                }
            }
        }

        return new FieldStats(maxDepth, complexity, aliasCount, hasIntrospection);
    }
}

