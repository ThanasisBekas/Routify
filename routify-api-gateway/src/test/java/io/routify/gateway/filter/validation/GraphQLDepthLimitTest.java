package io.routify.gateway.filter.validation;

import io.routify.gateway.filter.validation.GraphQLDepthLimitGatewayFilterFactory.Config;
import io.routify.gateway.filter.validation.GraphQLQueryAnalyzer.AnalysisResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for the limit-checking logic in {@link GraphQLDepthLimitGatewayFilterFactory}.
 * Tests the analyzer + limit checker in isolation (no reactive web exchange needed).
 */
class GraphQLDepthLimitTest {

    // ─── Depth limit checks ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Depth limit enforcement")
    class DepthLimitTests {

        @Test
        @DisplayName("depth 5 with maxDepth=10 → allowed")
        void depthUnderLimit() {
            var config = new Config();
            config.setMaxDepth(10);
            String query = """
                    {
                      user {
                        friends {
                          posts {
                            comments {
                              text
                            }
                          }
                        }
                      }
                    }
                    """;
            AnalysisResult result = GraphQLQueryAnalyzer.analyze(query);
            assertThat(result.depth()).isEqualTo(5);
            assertThat(GraphQLDepthLimitGatewayFilterFactory.GraphQLDepthLimitFilter.checkLimits(result, config)).isNull();
        }

        @Test
        @DisplayName("depth 15 with maxDepth=10 → rejected")
        void depthOverLimit() {
            var config = new Config();
            config.setMaxDepth(10);
            String query = """
                    {
                      a {
                        b {
                          c {
                            d {
                              e {
                                f {
                                  g {
                                    h {
                                      i {
                                        j {
                                          k {
                                            l {
                                              m {
                                                n {
                                                  name
                                                }
                                              }
                                            }
                                          }
                                        }
                                      }
                                    }
                                  }
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                    """;
            AnalysisResult result = GraphQLQueryAnalyzer.analyze(query);
            assertThat(result.depth()).isEqualTo(15);
            String violation = GraphQLDepthLimitGatewayFilterFactory.GraphQLDepthLimitFilter.checkLimits(result, config);
            assertThat(violation).isNotNull();
            assertThat(violation).contains("depth").contains("15").contains("10");
        }
    }

    // ─── Complexity limit checks ───────────────────────────────────────────────

    @Nested
    @DisplayName("Complexity limit enforcement")
    class ComplexityLimitTests {

        @Test
        @DisplayName("complexity 50 with maxComplexity=100 → allowed")
        void complexityUnderLimit() {
            var config = new Config();
            config.setMaxComplexity(100);
            // Build a query with exactly 50 fields (use aliases at top level)
            var result = new AnalysisResult(2, 50, 0, false);
            assertThat(GraphQLDepthLimitGatewayFilterFactory.GraphQLDepthLimitFilter.checkLimits(result, config)).isNull();
        }

        @Test
        @DisplayName("complexity 150 with maxComplexity=100 → rejected")
        void complexityOverLimit() {
            var config = new Config();
            config.setMaxComplexity(100);
            var result = new AnalysisResult(2, 150, 0, false);
            String violation = GraphQLDepthLimitGatewayFilterFactory.GraphQLDepthLimitFilter.checkLimits(result, config);
            assertThat(violation).isNotNull();
            assertThat(violation).contains("complexity").contains("150").contains("100");
        }
    }

    // ─── Alias limit checks ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Alias limit enforcement")
    class AliasLimitTests {

        @Test
        @DisplayName("3 aliases with maxAliases=5 → allowed")
        void aliasesUnderLimit() {
            var config = new Config();
            config.setMaxAliases(5);
            var result = new AnalysisResult(1, 6, 3, false);
            assertThat(GraphQLDepthLimitGatewayFilterFactory.GraphQLDepthLimitFilter.checkLimits(result, config)).isNull();
        }

        @Test
        @DisplayName("10 aliases with maxAliases=5 → rejected")
        void aliasesOverLimit() {
            var config = new Config();
            config.setMaxAliases(5);
            var result = new AnalysisResult(1, 20, 10, false);
            String violation = GraphQLDepthLimitGatewayFilterFactory.GraphQLDepthLimitFilter.checkLimits(result, config);
            assertThat(violation).isNotNull();
            assertThat(violation).contains("aliases").contains("10").contains("5");
        }
    }

    // ─── Introspection checks ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Introspection enforcement")
    class IntrospectionTests {

        @Test
        @DisplayName("introspection with introspectionAllowed=false → rejected")
        void introspectionBlocked() {
            var config = new Config();
            config.setIntrospectionAllowed(false);
            String query = """
                    {
                      __schema {
                        types {
                          name
                        }
                      }
                    }
                    """;
            AnalysisResult result = GraphQLQueryAnalyzer.analyze(query);
            assertThat(result.hasIntrospection()).isTrue();
            String violation = GraphQLDepthLimitGatewayFilterFactory.GraphQLDepthLimitFilter.checkLimits(result, config);
            assertThat(violation).isNotNull();
            assertThat(violation).containsIgnoringCase("introspection");
        }

        @Test
        @DisplayName("introspection with introspectionAllowed=true → allowed")
        void introspectionAllowed() {
            var config = new Config();
            config.setIntrospectionAllowed(true);
            var result = new AnalysisResult(2, 3, 0, true);
            assertThat(GraphQLDepthLimitGatewayFilterFactory.GraphQLDepthLimitFilter.checkLimits(result, config)).isNull();
        }
    }

    // ─── Non-GraphQL passthrough ───────────────────────────────────────────────

    @Nested
    @DisplayName("Non-GraphQL passthrough")
    class PassthroughTests {

        @Test
        @DisplayName("non-GraphQL request (no query field) passes through — no violation on normal JSON")
        void nonGraphQLRequest() {
            // The filter factory handles pass-through at the HTTP level.
            // Here we verify the analyzer produces sensible results for an empty-ish query.
            String query = "{ user { name } }";
            AnalysisResult result = GraphQLQueryAnalyzer.analyze(query);
            var config = new Config();
            // Default limits are generous — should pass
            assertThat(GraphQLDepthLimitGatewayFilterFactory.GraphQLDepthLimitFilter.checkLimits(result, config)).isNull();
        }
    }

    // ─── Batch query size ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Batch query scenarios (analyzer level)")
    class BatchTests {

        @Test
        @DisplayName("each operation in a batch is analyzed independently")
        void batchOperationsAnalyzed() {
            // Simulate checking two independent operations
            var config = new Config();
            config.setMaxDepth(3);

            String query1 = "{ user { name } }";
            String query2 = "{ user { friends { posts { title } } } }";

            AnalysisResult r1 = GraphQLQueryAnalyzer.analyze(query1);
            AnalysisResult r2 = GraphQLQueryAnalyzer.analyze(query2);

            assertThat(GraphQLDepthLimitGatewayFilterFactory.GraphQLDepthLimitFilter.checkLimits(r1, config)).isNull();
            // query2 has depth 4 > maxDepth 3
            assertThat(GraphQLDepthLimitGatewayFilterFactory.GraphQLDepthLimitFilter.checkLimits(r2, config)).isNotNull();
        }
    }

    // ─── Fragment spread depth ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Fragment spreads followed correctly in depth calculation")
    class FragmentDepthTests {

        @Test
        @DisplayName("fragment spread depth counted for limit checking")
        void fragmentDepthExceedsLimit() {
            var config = new Config();
            config.setMaxDepth(2);

            String query = """
                    query {
                      user {
                        ...UserFields
                      }
                    }
                    fragment UserFields on User {
                      name
                      friends {
                        name
                      }
                    }
                    """;
            AnalysisResult result = GraphQLQueryAnalyzer.analyze(query);
            // user(1) → fragment: name(2), friends(2) → name(3)
            assertThat(result.depth()).isEqualTo(3);
            String violation = GraphQLDepthLimitGatewayFilterFactory.GraphQLDepthLimitFilter.checkLimits(result, config);
            assertThat(violation).isNotNull();
            assertThat(violation).contains("depth");
        }
    }
}

