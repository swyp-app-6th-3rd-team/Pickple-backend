package app.pickple.docs;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.util.Json31;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 렌더러는 스프링에 의존하지 않으므로 컨테이너 없이 돈다.
 *
 * <p>OAS 트리를 손으로 만들어 넣는다. 실제 springdoc 출력을 쓰면 스펙이 바뀔 때마다
 * 테스트가 흔들리고, 정작 위험한 입력({@code $ref} 순환·깊은 중첩)은 현재 스펙에 없어서
 * 검증되지 않는다. 위험이 있는 곳에 테스트를 둔다.
 */
class OpenApiMarkdownRendererTest {

    private final OpenApiMarkdownRenderer renderer = new OpenApiMarkdownRenderer();

    private static JsonNode oas(String json) {
        try {
            return Json31.mapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("테스트 픽스처가 잘못된 JSON 이다", e);
        }
    }

    @Nested
    @DisplayName("$ref 순환 참조")
    class CyclicReference {

        /**
         * 이 테스트가 이 클래스의 존재 이유다.
         *
         * <p>가드가 없으면 {@code StackOverflowError} 로 500 이 난다.
         * 재귀가 멈추는지를 보는 것이므로 반환값보다 <b>유한 시간 내 종료</b>가 본질이다.
         */
        @Test
        @DisplayName("자기 자신을 참조하는 스키마도 유한 시간 안에 렌더된다")
        void selfReferenceTerminates() {
            JsonNode spec = oas("""
                    {
                      "paths": {
                        "/tree": {
                          "get": {
                            "tags": ["Tree"],
                            "summary": "트리 조회",
                            "responses": {
                              "200": {
                                "content": {
                                  "application/json": {
                                    "schema": { "$ref": "#/components/schemas/Node" }
                                  }
                                }
                              }
                            }
                          }
                        }
                      },
                      "components": {
                        "schemas": {
                          "Node": {
                            "type": "object",
                            "properties": {
                              "name": { "type": "string" },
                              "children": {
                                "type": "array",
                                "items": { "$ref": "#/components/schemas/Node" }
                              }
                            }
                          }
                        }
                      }
                    }
                    """);

            assertThatCode(() -> renderer.render(spec))
                    .describedAs("순환 참조에서 StackOverflowError 가 나면 안 된다")
                    .doesNotThrowAnyException();

            assertThat(renderer.render(spec)).contains("순환 참조");
        }

        @Test
        @DisplayName("A → B → A 상호 순환도 끊어낸다")
        void mutualReferenceTerminates() {
            // 직전 부모만 기억하는 가드는 자기참조는 막지만 이건 못 막는다.
            JsonNode spec = oas("""
                    {
                      "paths": {
                        "/a": {
                          "get": {
                            "tags": ["A"],
                            "responses": {
                              "200": {
                                "content": {
                                  "application/json": {
                                    "schema": { "$ref": "#/components/schemas/A" }
                                  }
                                }
                              }
                            }
                          }
                        }
                      },
                      "components": {
                        "schemas": {
                          "A": {
                            "type": "object",
                            "properties": { "b": { "$ref": "#/components/schemas/B" } }
                          },
                          "B": {
                            "type": "object",
                            "properties": { "a": { "$ref": "#/components/schemas/A" } }
                          }
                        }
                      }
                    }
                    """);

            assertThatCode(() -> renderer.render(spec)).doesNotThrowAnyException();
            assertThat(renderer.render(spec)).contains("순환 참조");
        }

        /**
         * 순환이 아닌데 순환으로 오인하면 안 된다.
         *
         * <p>{@code visited} 를 전역 누적 집합으로 만들면 이 테스트가 깨진다.
         * 형제 필드가 같은 타입을 두 번 참조하는 건 정상이고, 두 번 다 펼쳐져야 한다.
         * 되돌리기(backtracking)가 있어야 <b>조상 순환만</b> 걸린다.
         */
        @Test
        @DisplayName("형제가 같은 타입을 참조하는 다이아몬드는 양쪽 다 펼친다")
        void diamondIsNotACycle() {
            JsonNode spec = oas("""
                    {
                      "paths": {
                        "/orders": {
                          "get": {
                            "tags": ["Order"],
                            "responses": {
                              "200": {
                                "content": {
                                  "application/json": {
                                    "schema": { "$ref": "#/components/schemas/Order" }
                                  }
                                }
                              }
                            }
                          }
                        }
                      },
                      "components": {
                        "schemas": {
                          "Order": {
                            "type": "object",
                            "properties": {
                              "billing": { "$ref": "#/components/schemas/Address" },
                              "shipping": { "$ref": "#/components/schemas/Address" }
                            }
                          },
                          "Address": {
                            "type": "object",
                            "properties": { "city": { "type": "string" } }
                          }
                        }
                      }
                    }
                    """);

            String markdown = renderer.render(spec);

            assertThat(markdown)
                    .describedAs("billing·shipping 양쪽에서 city 가 펼쳐져야 한다")
                    .containsSubsequence("billing", "city", "shipping", "city");
            assertThat(markdown).doesNotContain("순환 참조");
        }

        @Test
        @DisplayName("$ref 없는 익명 중첩도 깊이 상한에서 끊는다")
        void inlineNestingHitsDepthCap() {
            // visited 집합은 이름 있는 순환만 잡는다. 이름이 없는 무한 중첩은
            // 깊이 상한이라는 두 번째 안전벨트가 막는다.
            StringBuilder nested = new StringBuilder();
            int depth = 20;
            for (int i = 0; i < depth; i++) {
                nested.append("{\"type\":\"object\",\"properties\":{\"child\":");
            }
            nested.append("{\"type\":\"string\"}");
            nested.append("}}".repeat(depth));

            JsonNode spec = oas("""
                    {
                      "paths": {
                        "/deep": {
                          "get": {
                            "tags": ["Deep"],
                            "responses": {
                              "200": {
                                "content": {
                                  "application/json": { "schema": %s }
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                    """.formatted(nested));

            assertThatCode(() -> renderer.render(spec)).doesNotThrowAnyException();
            assertThat(renderer.render(spec)).contains("깊이 제한");
        }
    }

    @Nested
    @DisplayName("스키마 전개")
    class SchemaExpansion {

        @Test
        @DisplayName("allOf 는 프로퍼티를 하나로 병합한다")
        void allOfMergesProperties() {
            JsonNode spec = oas("""
                    {
                      "paths": {
                        "/x": {
                          "get": {
                            "tags": ["X"],
                            "responses": {
                              "200": {
                                "content": {
                                  "application/json": {
                                    "schema": {
                                      "allOf": [
                                        { "type": "object", "properties": { "id": { "type": "integer" } } },
                                        { "type": "object", "properties": { "name": { "type": "string" } } }
                                      ]
                                    }
                                  }
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                    """);

            assertThat(renderer.render(spec)).contains("id").contains("name");
        }

        @Test
        @DisplayName("oneOf 는 분기를 모두 보여준다")
        void oneOfShowsBranches() {
            JsonNode spec = oas("""
                    {
                      "paths": {
                        "/x": {
                          "get": {
                            "tags": ["X"],
                            "responses": {
                              "200": {
                                "content": {
                                  "application/json": {
                                    "schema": {
                                      "oneOf": [
                                        { "type": "object", "properties": { "cash": { "type": "integer" } } },
                                        { "type": "object", "properties": { "card": { "type": "string" } } }
                                      ]
                                    }
                                  }
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                    """);

            String markdown = renderer.render(spec);
            assertThat(markdown).contains("다음 중 하나").contains("cash").contains("card");
        }

        @Test
        @DisplayName("배열의 $ref 는 원소 모양까지 펼친다")
        void arrayOfRefIsInlined() {
            JsonNode spec = oas("""
                    {
                      "paths": {
                        "/users": {
                          "get": {
                            "tags": ["User"],
                            "responses": {
                              "200": {
                                "content": {
                                  "application/json": {
                                    "schema": {
                                      "type": "array",
                                      "items": { "$ref": "#/components/schemas/User" }
                                    }
                                  }
                                }
                              }
                            }
                          }
                        }
                      },
                      "components": {
                        "schemas": {
                          "User": {
                            "type": "object",
                            "properties": { "email": { "type": "string" } }
                          }
                        }
                      }
                    }
                    """);

            String markdown = renderer.render(spec);
            assertThat(markdown)
                    .describedAs("$ref 문자열이 그대로 남으면 LLM 이 스키마를 알 수 없다")
                    .doesNotContain("#/components/schemas/User");
            assertThat(markdown).contains("email");
        }

        @Test
        @DisplayName("정의가 없는 $ref 는 NPE 대신 표시로 남는다")
        void danglingRefDegradesGracefully() {
            JsonNode spec = oas("""
                    {
                      "paths": {
                        "/x": {
                          "get": {
                            "tags": ["X"],
                            "responses": {
                              "200": {
                                "content": {
                                  "application/json": {
                                    "schema": { "$ref": "#/components/schemas/Missing" }
                                  }
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                    """);

            assertThatCode(() -> renderer.render(spec)).doesNotThrowAnyException();
            assertThat(renderer.render(spec)).contains("Missing").contains("정의 없음");
        }

        /**
         * OAS 3.1 은 {@code type} 대신 {@code types} 배열을 채우는 경우가 있다.
         * {@code type} 만 읽으면 모든 필드가 "any" 로 나오는데, 렌더는 성공하므로
         * 테스트가 없으면 조용히 쓸모없는 문서가 나간다.
         */
        @Test
        @DisplayName("type 이 없고 types 만 있어도 타입을 알아본다")
        void fallsBackToTypesArray() {
            JsonNode spec = oas("""
                    {
                      "paths": {
                        "/x": {
                          "get": {
                            "tags": ["X"],
                            "responses": {
                              "200": {
                                "content": {
                                  "application/json": {
                                    "schema": {
                                      "type": "object",
                                      "properties": { "nickname": { "types": ["string"] } }
                                    }
                                  }
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                    """);

            assertThat(renderer.render(spec))
                    .describedAs("types 폴백이 없으면 문자열이 any 로 렌더된다")
                    .doesNotContain("nickname  any");
        }

        @Test
        @DisplayName("required 목록에 따라 필수와 선택을 구분한다")
        void marksRequiredFields() {
            JsonNode spec = oas("""
                    {
                      "paths": {
                        "/x": {
                          "post": {
                            "tags": ["X"],
                            "requestBody": {
                              "content": {
                                "application/json": {
                                  "schema": {
                                    "type": "object",
                                    "required": ["email"],
                                    "properties": {
                                      "email": { "type": "string" },
                                      "memo": { "type": "string" }
                                    }
                                  }
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                    """);

            String markdown = renderer.render(spec);
            assertThat(markdown).containsSubsequence("email", "필수");
            assertThat(markdown).containsSubsequence("memo", "선택");
        }
    }

    @Nested
    @DisplayName("문서 구조")
    class DocumentStructure {

        @Test
        @DisplayName("태그별로 묶고 오퍼레이션마다 메서드와 경로를 적는다")
        void groupsByTag() {
            JsonNode spec = oas("""
                    {
                      "paths": {
                        "/auth/me": {
                          "get": {
                            "tags": ["Auth"],
                            "summary": "내 정보",
                            "description": "Bearer 토큰이 필요하다."
                          }
                        }
                      },
                      "tags": [ { "name": "Auth", "description": "소셜 로그인" } ]
                    }
                    """);

            String markdown = renderer.render(spec);
            assertThat(markdown).contains("## Auth");
            assertThat(markdown).contains("### GET /auth/me");
            assertThat(markdown).contains("내 정보");
        }

        @Test
        @DisplayName("경로가 없어도 깨지지 않는다")
        void handlesEmptySpec() {
            assertThatCode(() -> renderer.render(oas("{}"))).doesNotThrowAnyException();
        }

        /**
         * 모든 응답이 같은 봉투로 감싸이므로 봉투는 머리말에 한 번만 적는다.
         * 오퍼레이션마다 code·message 를 반복하면 같은 3필드가 문서 전체에 복제돼 토큰만 먹는다.
         */
        @Test
        @DisplayName("ApiResponse 봉투는 벗기고 returnObject 안쪽만 보여준다")
        void unwrapsResponseEnvelope() {
            JsonNode spec = oas("""
                    {
                      "paths": {
                        "/auth/me": {
                          "get": {
                            "tags": ["Auth"],
                            "responses": {
                              "200": {
                                "content": {
                                  "application/json": {
                                    "schema": { "$ref": "#/components/schemas/ApiResponseMeResponse" }
                                  }
                                }
                              }
                            }
                          }
                        }
                      },
                      "components": {
                        "schemas": {
                          "ApiResponseMeResponse": {
                            "type": "object",
                            "properties": {
                              "code": { "type": "string" },
                              "message": { "type": "string" },
                              "returnObject": { "$ref": "#/components/schemas/MeResponse" }
                            }
                          },
                          "MeResponse": {
                            "type": "object",
                            "properties": { "userId": { "type": "integer" } }
                          }
                        }
                      }
                    }
                    """);

            String markdown = renderer.render(spec);
            String body = markdown.substring(markdown.indexOf("### GET"));

            assertThat(body)
                    .describedAs("봉투 필드는 머리말에만 있어야 한다")
                    .doesNotContain("message");
            assertThat(body).contains("userId");
        }

        @Test
        @DisplayName("봉투 모양이 아닌 응답은 그대로 보여준다")
        void keepsNonEnvelopeResponseAsIs() {
            JsonNode spec = oas("""
                    {
                      "paths": {
                        "/raw": {
                          "get": {
                            "tags": ["Raw"],
                            "responses": {
                              "200": {
                                "content": {
                                  "application/json": {
                                    "schema": {
                                      "type": "object",
                                      "properties": { "token": { "type": "string" } }
                                    }
                                  }
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                    """);

            assertThat(renderer.render(spec)).contains("token");
        }

        @Test
        @DisplayName("태그를 지정하면 그 태그의 오퍼레이션만 남는다")
        void filtersByTag() {
            JsonNode spec = oas("""
                    {
                      "paths": {
                        "/auth/me": { "get": { "tags": ["Auth"], "summary": "내 정보" } },
                        "/orders":  { "get": { "tags": ["Order"], "summary": "주문 목록" } }
                      }
                    }
                    """);

            String markdown = renderer.render(spec, "Auth");
            assertThat(markdown).contains("/auth/me");
            assertThat(markdown).doesNotContain("/orders");
        }
    }
}
