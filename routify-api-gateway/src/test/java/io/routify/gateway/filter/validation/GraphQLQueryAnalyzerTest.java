package io.routify.gateway.filter.validation;

import io.routify.gateway.filter.validation.GraphQLQueryAnalyzer.AnalysisResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import graphql.parser.InvalidSyntaxException;

import static org.assertj.core.api.Assertions.*;

class GraphQLQueryAnalyzerTest {

    // ─── Depth ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Depth analysis")
    class DepthTests {

        @Test
        @DisplayName("simple query has depth 1")
        void simpleQuery() {
            AnalysisResult result = GraphQLQueryAnalyzer.analyze("{ user }");
            assertThat(result.depth()).isEqualTo(1);
        }

        @Test
        @DisplayName("nested query has correct depth")
        void nestedQuery() {
            String query = """
                    {
                      user {
                        friends {
                          name
                        }
                      }
                    }
                    """;
            AnalysisResult result = GraphQLQueryAnalyzer.analyze(query);
            assertThat(result.depth()).isEqualTo(3);
        }

        @Test
        @DisplayName("deeply nested query computes correct depth")
        void deeplyNestedQuery() {
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
        }

        @Test
        @DisplayName("depth 5 with maxDepth=10 → allowed")
        void depthUnderLimit() {
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
            // Checking limit is done at the filter level, but verify depth is correct
        }
    }

    // ─── Complexity ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Complexity analysis")
    class ComplexityTests {

        @Test
        @DisplayName("counts all fields for complexity")
        void fieldCount() {
            String query = """
                    {
                      user {
                        name
                        email
                        age
                      }
                    }
                    """;
            AnalysisResult result = GraphQLQueryAnalyzer.analyze(query);
            // user (1) + name (1) + email (1) + age (1) = 4
            assertThat(result.complexity()).isEqualTo(4);
        }

        @Test
        @DisplayName("nested fields counted correctly")
        void nestedFieldCount() {
            String query = """
                    {
                      user {
                        friends {
                          name
                          email
                        }
                        posts {
                          title
                        }
                      }
                    }
                    """;
            // user(1) + friends(1) + name(1) + email(1) + posts(1) + title(1) = 6
            AnalysisResult result = GraphQLQueryAnalyzer.analyze(query);
            assertThat(result.complexity()).isEqualTo(6);
        }
    }

    // ─── Aliases ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Alias counting")
    class AliasTests {

        @Test
        @DisplayName("counts aliases correctly")
        void aliasCount() {
            String query = """
                    {
                      u1: user(id: 1) { name }
                      u2: user(id: 2) { name }
                      u3: user(id: 3) { name }
                    }
                    """;
            AnalysisResult result = GraphQLQueryAnalyzer.analyze(query);
            assertThat(result.aliasCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("no aliases yields zero")
        void noAliases() {
            String query = "{ user { name } }";
            AnalysisResult result = GraphQLQueryAnalyzer.analyze(query);
            assertThat(result.aliasCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("10 aliases detected")
        void manyAliases() {
            StringBuilder sb = new StringBuilder("{ ");
            for (int i = 0; i < 10; i++) {
                sb.append("a%d: user(id: %d) { name } ".formatted(i, i));
            }
            sb.append("}");
            AnalysisResult result = GraphQLQueryAnalyzer.analyze(sb.toString());
            assertThat(result.aliasCount()).isEqualTo(10);
        }
    }

    // ─── Introspection ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Introspection detection")
    class IntrospectionTests {

        @Test
        @DisplayName("__schema query detected")
        void schemaIntrospection() {
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
        }

        @Test
        @DisplayName("__type query detected")
        void typeIntrospection() {
            String query = """
                    {
                      __type(name: "User") {
                        name
                        fields {
                          name
                        }
                      }
                    }
                    """;
            AnalysisResult result = GraphQLQueryAnalyzer.analyze(query);
            assertThat(result.hasIntrospection()).isTrue();
        }

        @Test
        @DisplayName("normal query has no introspection")
        void noIntrospection() {
            String query = "{ user { name } }";
            AnalysisResult result = GraphQLQueryAnalyzer.analyze(query);
            assertThat(result.hasIntrospection()).isFalse();
        }
    }

    // ─── Fragment spreads ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Fragment spread resolution")
    class FragmentTests {

        @Test
        @DisplayName("fragment spreads followed correctly in depth calculation")
        void fragmentDepth() {
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
            // user(depth=1) → fragment: name(depth=2), friends(depth=2) → name(depth=3)
            assertThat(result.depth()).isEqualTo(3);
        }

        @Test
        @DisplayName("deeply nested fragments accumulate depth")
        void deepFragments() {
            String query = """
                    query {
                      user {
                        ...F1
                      }
                    }
                    fragment F1 on User {
                      friends {
                        ...F2
                      }
                    }
                    fragment F2 on User {
                      posts {
                        title
                      }
                    }
                    """;
            AnalysisResult result = GraphQLQueryAnalyzer.analyze(query);
            // user(1) → friends(2) → posts(3) → title(4)
            assertThat(result.depth()).isEqualTo(4);
        }
    }

    // ─── Invalid queries ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Invalid query handling")
    class InvalidQueryTests {

        @Test
        @DisplayName("invalid GraphQL query throws InvalidSyntaxException")
        void invalidQuery() {
            assertThatThrownBy(() -> GraphQLQueryAnalyzer.analyze("this is not GraphQL"))
                    .isInstanceOf(InvalidSyntaxException.class);
        }

        @Test
        @DisplayName("empty query throws")
        void emptyQuery() {
            assertThatThrownBy(() -> GraphQLQueryAnalyzer.analyze(""))
                    .isInstanceOf(InvalidSyntaxException.class);
        }
    }

    // ─── Inline fragments ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Inline fragment handling")
    class InlineFragmentTests {

        @Test
        @DisplayName("inline fragments contribute to depth and complexity")
        void inlineFragments() {
            String query = """
                    {
                      search(text: "hello") {
                        ... on User {
                          name
                          friends {
                            name
                          }
                        }
                        ... on Post {
                          title
                        }
                      }
                    }
                    """;
            AnalysisResult result = GraphQLQueryAnalyzer.analyze(query);
            // search(1) → inline User: name(2), friends(2) → name(3); inline Post: title(2)
            assertThat(result.depth()).isEqualTo(3);
        }
    }
}

