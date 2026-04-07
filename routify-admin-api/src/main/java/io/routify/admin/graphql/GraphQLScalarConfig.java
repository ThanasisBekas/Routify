package io.routify.admin.graphql;

import graphql.language.StringValue;
import graphql.schema.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Custom scalar type configuration for the GraphQL Analytics API.
 *
 * <p>Registers {@code DateTime} (ISO-8601 Instant), {@code Date} (ISO-8601 LocalDate),
 * and {@code Long} scalar types used throughout the analytics schema.
 */
@Configuration
@SuppressWarnings("deprecation") // Coercing serialize/parseValue/parseLiteral are deprecated but still standard
public class GraphQLScalarConfig {

    @Bean
    public RuntimeWiringConfigurer runtimeWiringConfigurer() {
        return wiringBuilder -> wiringBuilder
                .scalar(dateTimeScalar())
                .scalar(dateScalar())
                .scalar(longScalar());
    }

    private GraphQLScalarType dateTimeScalar() {
        return GraphQLScalarType.newScalar()
                .name("DateTime")
                .description("ISO-8601 date-time (e.g. 2026-01-15T10:30:00Z)")
                .coercing(new Coercing<Instant, String>() {
                    @Override
                    public String serialize(Object dataFetcherResult) {
                        if (dataFetcherResult instanceof Instant instant) {
                            return instant.toString();
                        }
                        if (dataFetcherResult instanceof String s) {
                            return s;
                        }
                        throw new CoercingSerializeException(
                                "Expected Instant or String but got " + dataFetcherResult.getClass().getSimpleName());
                    }

                    @Override
                    public Instant parseValue(Object input) {
                        if (input instanceof String s) {
                            try {
                                return Instant.parse(s);
                            } catch (DateTimeParseException e) {
                                throw new CoercingParseValueException("Invalid DateTime: " + s);
                            }
                        }
                        throw new CoercingParseValueException(
                                "Expected a String but got " + input.getClass().getSimpleName());
                    }

                    @Override
                    public Instant parseLiteral(Object input) {
                        if (input instanceof StringValue sv) {
                            try {
                                return Instant.parse(sv.getValue());
                            } catch (DateTimeParseException e) {
                                throw new CoercingParseLiteralException("Invalid DateTime literal: " + sv.getValue());
                            }
                        }
                        throw new CoercingParseLiteralException("Expected a StringValue");
                    }
                })
                .build();
    }

    private GraphQLScalarType dateScalar() {
        return GraphQLScalarType.newScalar()
                .name("Date")
                .description("ISO-8601 date (e.g. 2026-01-15)")
                .coercing(new Coercing<LocalDate, String>() {
                    @Override
                    public String serialize(Object dataFetcherResult) {
                        if (dataFetcherResult instanceof LocalDate ld) {
                            return ld.toString();
                        }
                        if (dataFetcherResult instanceof String s) {
                            return s;
                        }
                        throw new CoercingSerializeException(
                                "Expected LocalDate or String but got " + dataFetcherResult.getClass().getSimpleName());
                    }

                    @Override
                    public LocalDate parseValue(Object input) {
                        if (input instanceof String s) {
                            try {
                                return LocalDate.parse(s);
                            } catch (DateTimeParseException e) {
                                throw new CoercingParseValueException("Invalid Date: " + s);
                            }
                        }
                        throw new CoercingParseValueException(
                                "Expected a String but got " + input.getClass().getSimpleName());
                    }

                    @Override
                    public LocalDate parseLiteral(Object input) {
                        if (input instanceof StringValue sv) {
                            try {
                                return LocalDate.parse(sv.getValue());
                            } catch (DateTimeParseException e) {
                                throw new CoercingParseLiteralException("Invalid Date literal: " + sv.getValue());
                            }
                        }
                        throw new CoercingParseLiteralException("Expected a StringValue");
                    }
                })
                .build();
    }

    private GraphQLScalarType longScalar() {
        return GraphQLScalarType.newScalar()
                .name("Long")
                .description("64-bit integer")
                .coercing(new Coercing<Long, Long>() {
                    @Override
                    public Long serialize(Object dataFetcherResult) {
                        if (dataFetcherResult instanceof Number n) {
                            return n.longValue();
                        }
                        throw new CoercingSerializeException(
                                "Expected a Number but got " + dataFetcherResult.getClass().getSimpleName());
                    }

                    @Override
                    public Long parseValue(Object input) {
                        if (input instanceof Number n) {
                            return n.longValue();
                        }
                        if (input instanceof String s) {
                            try {
                                return Long.parseLong(s);
                            } catch (NumberFormatException e) {
                                throw new CoercingParseValueException("Invalid Long: " + s);
                            }
                        }
                        throw new CoercingParseValueException(
                                "Expected Number or String but got " + input.getClass().getSimpleName());
                    }

                    @Override
                    public Long parseLiteral(Object input) {
                        if (input instanceof graphql.language.IntValue iv) {
                            return iv.getValue().longValue();
                        }
                        if (input instanceof StringValue sv) {
                            try {
                                return Long.parseLong(sv.getValue());
                            } catch (NumberFormatException e) {
                                throw new CoercingParseLiteralException("Invalid Long literal: " + sv.getValue());
                            }
                        }
                        throw new CoercingParseLiteralException("Expected an IntValue or StringValue");
                    }
                })
                .build();
    }
}

