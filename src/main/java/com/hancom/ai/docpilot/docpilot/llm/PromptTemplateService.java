package com.hancom.ai.docpilot.docpilot.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
public class PromptTemplateService {

    private static final int MAX_CODE_LENGTH = 16000;
    private static final String TRUNCATION_SUFFIX = "\n...(이하 생략)";

    private static final Map<String, String> TEMPLATES = Map.ofEntries(
            Map.entry("service_overview", """
                    당신은 마크다운을 Confluence 문서로 변환하는 전문가입니다.
                    아래는 프로젝트 "%s"의 README.md 파일 내용입니다.
                    이 마크다운 내용을 Confluence Storage Format(XML)으로 정확히 변환해주세요.

                    변환 규칙:
                    - 마크다운 헤더(#, ##, ###)를 <h1>, <h2>, <h3> 태그로 변환
                    - 마크다운 리스트(-, *)를 <ul><li> 태그로 변환
                    - 마크다운 코드블록을 <ac:structured-macro ac:name="code"> 매크로로 변환
                    - 마크다운 테이블을 <table> 태그로 변환
                    - 마크다운 링크를 <a> 태그로 변환
                    - 마크다운 굵은 글씨(**)를 <strong> 태그로 변환
                    - 원본 내용을 그대로 유지하되, 형식만 Confluence XML로 변환

                    중요: 내용을 수정하거나 요약하지 마세요. 원본 README.md의 내용을 있는 그대로 변환만 하세요.
                    반드시 Confluence Storage Format(XML)으로만 응답하세요. 코드블록 마커(예: xml), 설명, 마크다운은 포함하지 마세요.

                    README.md 내용:
                    %s
                    """),

            Map.entry("api_spec", """
                    당신은 한국어 API 문서 작성 전문가입니다.
                    아래 프로젝트 "%s"의 코드를 분석하여 REST API 명세서를 한국어로 작성해주세요.

                    작성 요구사항:
                    - 각 엔드포인트의 HTTP 메서드, URL 경로, 설명
                    - 요청 파라미터 (Path, Query, Body)
                    - 응답 형식 및 상태 코드
                    - 테이블 형식으로 정리

                    중요: 모든 내용을 반드시 한국어로 작성하세요. URL 경로, HTTP 메서드, 파라미터명을 제외한 모든 설명은 한국어여야 합니다.
                    반드시 Confluence Storage Format(XML)으로만 응답하세요. 코드블록 마커(예: xml), 설명, 마크다운은 포함하지 마세요.

                    코드:
                    %s
                    """),

            Map.entry("controller_analysis", """
                    당신은 Java Spring 코드 분석 전문가입니다.
                    아래 Controller 코드를 분석하여 JSON 형식으로 결과를 반환해주세요.

                    분석할 프로젝트: "%s"

                    반환 형식 (반드시 이 JSON 구조만 응답하세요, 다른 텍스트 없이):
                    {
                      "controllerKoreanName": "이 Controller의 기능을 나타내는 한국어 이름 (예: 인증, 챗봇 도메인, 사용자 관리)",
                      "apis": [
                        {
                          "method": "POST",
                          "path": "/api/example/path",
                          "methodName": "자바 메서드명",
                          "koreanName": "이 API의 기능을 나타내는 한국어 이름 (예: 로그아웃, 도메인 추가)"
                        }
                      ]
                    }

                    규칙:
                    - controllerKoreanName은 Controller의 역할을 간결한 한국어로 표현 (2~4글자)
                    - 각 API의 koreanName은 해당 API의 기능을 간결한 한국어로 표현 (2~6글자)
                    - @RequestMapping, @GetMapping, @PostMapping, @PutMapping, @DeleteMapping 등의 어노테이션에서 경로와 메서드를 추출
                    - 클래스 레벨 @RequestMapping의 경로를 각 API 경로의 prefix로 포함

                    Controller 코드:
                    %s
                    """),

            Map.entry("api_detail", """
                    당신은 한국어 API 문서 작성 전문가입니다.
                    아래 코드에서 해당 API 엔드포인트의 상세 명세서를 한국어로 작성해주세요.

                    프로젝트: "%s"

                    규칙:
                    - Request Parameter, Request Body, Response는 표(테이블) 형식으로 작성
                    - 해당 필드가 없더라도 빈 표를 반드시 생성하세요
                    - Request Example은 반드시 작성하세요
                    - Response Example은 예상이 어려우면 비워두어도 됩니다
                    - Request URL 형식: [HTTP메서드] {domain}/전체/경로
                    - 클래스 레벨 @RequestMapping 경로를 prefix로 포함하여 전체 경로를 작성
                    - DTO/Bean 소스가 제공된 경우, 해당 클래스의 모든 필드를 Request/Response 테이블에 포함하세요
                    - "DTO 소스를 찾지 못했습니다"라는 메시지가 있는 경우, 해당 섹션 상단에 붉은색 경고를 추가하세요:
                      <p><span style="color: #ff0000;"><strong>※ 해당 DTO의 소스를 찾지 못하여 필드 정보가 부정확할 수 있습니다.</strong></span></p>

                    중요: 모든 내용을 반드시 한국어로 작성하세요.
                    반드시 Confluence Storage Format(XML)으로만 응답하세요. 코드블록 마커, 설명, 마크다운은 포함하지 마세요.

                    %s
                    """),

            Map.entry("api_detail_with_template", """
                    당신은 한국어 API 문서 작성 전문가입니다.
                    아래 코드에서 해당 API 엔드포인트의 상세 명세서를 한국어로 작성해주세요.

                    프로젝트: "%s"

                    [서식 규칙 - 최우선 준수사항]
                    아래 "서식 예시"의 Confluence Storage Format XML을 정확히 복제하여 작성하세요:
                    - 서식 예시의 섹션 순서를 그대로 따르세요
                    - 서식 예시에 있는 모든 XML 태그, 속성(style, class, data-layout 등), 매크로(ac:structured-macro)를 동일하게 사용하세요
                    - 테이블의 colgroup, col width, th/td style 속성을 서식 예시와 동일하게 복사하세요
                    - 레이아웃(너비, 정렬 등)에 관한 XML 속성을 절대 생략하지 마세요
                    - 내용만 대상 API에 맞게 교체하고, XML 구조는 서식 예시를 그대로 사용하세요

                    [내용 규칙]
                    - Request Parameter, Request Body, Response는 표(테이블) 형식으로 작성
                    - 해당 필드가 없더라도 빈 표를 반드시 생성하세요
                    - Request Example은 반드시 작성하세요
                    - Response Example은 예상이 어려우면 비워두어도 됩니다
                    - Request URL 형식: [HTTP메서드] {domain}/전체/경로
                    - 클래스 레벨 @RequestMapping 경로를 prefix로 포함하여 전체 경로를 작성
                    - DTO/Bean 소스가 제공된 경우, 해당 클래스의 모든 필드를 Request/Response 테이블에 포함하세요
                    - "DTO 소스를 찾지 못했습니다"라는 메시지가 있는 경우, 해당 섹션 상단에 붉은색 경고를 추가하세요:
                      <p><span style="color: #ff0000;"><strong>※ 해당 DTO의 소스를 찾지 못하여 필드 정보가 부정확할 수 있습니다.</strong></span></p>

                    중요: 모든 내용을 반드시 한국어로 작성하세요.
                    반드시 Confluence Storage Format(XML)으로만 응답하세요. 코드블록 마커, 설명, 마크다운은 포함하지 마세요.

                    === 서식 예시 (이 XML 구조를 그대로 복제하세요) ===
                    %s

                    === 대상 API 코드 ===
                    %s
                    """),

            Map.entry("django_controller_analysis", """
                    당신은 Python Django/DRF 코드 분석 전문가입니다.
                    아래 View 파일의 코드를 분석하여 JSON 형식으로 결과를 반환해주세요.

                    분석할 프로젝트: "%s"

                    반환 형식 (반드시 이 JSON 구조만 응답하세요, 다른 텍스트 없이):
                    {
                      "controllerKoreanName": "이 View 파일의 기능을 나타내는 한국어 이름 (예: 인증, 사용자 관리, 채팅)",
                      "apis": [
                        {
                          "method": "POST",
                          "path": "/api/example/path",
                          "methodName": "파이썬 함수명 또는 클래스명.메서드명",
                          "koreanName": "이 API의 기능을 나타내는 한국어 이름 (예: 로그인, 목록조회)"
                        }
                      ]
                    }

                    규칙:
                    - controllerKoreanName은 View 파일의 역할을 간결한 한국어로 표현 (2~4글자)
                    - 각 API의 koreanName은 해당 API의 기능을 간결한 한국어로 표현 (2~6글자)
                    - @api_view 데코레이터에서 HTTP 메서드를 추출
                    - class-based view(APIView)의 get/post/put/delete/patch 메서드에서 HTTP 메서드를 추출
                    - ViewSet의 list→GET, create→POST, retrieve→GET, update→PUT, partial_update→PATCH, destroy→DELETE로 매핑
                    - ViewSet의 @action 데코레이터에서 HTTP 메서드와 경로를 추출
                    - urls.py 내용이 제공된 경우, URL 패턴에서 경로를 추출하여 path에 반영
                    - router.register 패턴에서 ViewSet의 기본 경로를 추출
                    - methodName은 함수 기반 뷰는 함수명, 클래스 기반 뷰는 "클래스명.메서드명" 형식으로 작성

                    View 코드:
                    %s
                    """),

            Map.entry("django_api_detail", """
                    당신은 한국어 API 문서 작성 전문가입니다.
                    아래 Python Django/DRF 코드에서 해당 API 엔드포인트의 상세 명세서를 한국어로 작성해주세요.

                    프로젝트: "%s"

                    규칙:
                    - Request Parameter, Request Body, Response는 표(테이블) 형식으로 작성
                    - 해당 필드가 없더라도 빈 표를 반드시 생성하세요
                    - Request Example은 반드시 작성하세요
                    - Response Example은 예상이 어려우면 비워두어도 됩니다
                    - Request URL 형식: [HTTP메서드] {domain}/전체/경로
                    - request.data → Request Body로 해석
                    - request.query_params, request.GET → Query Parameter로 해석
                    - URL kwargs (pk, id 등) → Path Parameter로 해석
                    - Serializer 소스가 제공된 경우, 해당 클래스의 모든 필드를 Request/Response 테이블에 포함하세요
                    - "Serializer 소스를 찾지 못했습니다"라는 메시지가 있는 경우, 해당 섹션 상단에 붉은색 경고를 추가하세요:
                      <p><span style="color: #ff0000;"><strong>※ 해당 Serializer의 소스를 찾지 못하여 필드 정보가 부정확할 수 있습니다.</strong></span></p>

                    중요: 모든 내용을 반드시 한국어로 작성하세요.
                    반드시 Confluence Storage Format(XML)으로만 응답하세요. 코드블록 마커, 설명, 마크다운은 포함하지 마세요.

                    %s
                    """),

            Map.entry("django_api_detail_with_template", """
                    당신은 한국어 API 문서 작성 전문가입니다.
                    아래 Python Django/DRF 코드에서 해당 API 엔드포인트의 상세 명세서를 한국어로 작성해주세요.

                    프로젝트: "%s"

                    [서식 규칙 - 최우선 준수사항]
                    아래 "서식 예시"의 Confluence Storage Format XML을 정확히 복제하여 작성하세요:
                    - 서식 예시의 섹션 순서를 그대로 따르세요
                    - 서식 예시에 있는 모든 XML 태그, 속성(style, class, data-layout 등), 매크로(ac:structured-macro)를 동일하게 사용하세요
                    - 테이블의 colgroup, col width, th/td style 속성을 서식 예시와 동일하게 복사하세요
                    - 레이아웃(너비, 정렬 등)에 관한 XML 속성을 절대 생략하지 마세요
                    - 내용만 대상 API에 맞게 교체하고, XML 구조는 서식 예시를 그대로 사용하세요

                    [내용 규칙]
                    - Request Parameter, Request Body, Response는 표(테이블) 형식으로 작성
                    - 해당 필드가 없더라도 빈 표를 반드시 생성하세요
                    - Request Example은 반드시 작성하세요
                    - Response Example은 예상이 어려우면 비워두어도 됩니다
                    - Request URL 형식: [HTTP메서드] {domain}/전체/경로
                    - request.data → Request Body로 해석
                    - request.query_params, request.GET → Query Parameter로 해석
                    - URL kwargs (pk, id 등) → Path Parameter로 해석
                    - Serializer 소스가 제공된 경우, 해당 클래스의 모든 필드를 Request/Response 테이블에 포함하세요
                    - "Serializer 소스를 찾지 못했습니다"라는 메시지가 있는 경우, 해당 섹션 상단에 붉은색 경고를 추가하세요:
                      <p><span style="color: #ff0000;"><strong>※ 해당 Serializer의 소스를 찾지 못하여 필드 정보가 부정확할 수 있습니다.</strong></span></p>

                    중요: 모든 내용을 반드시 한국어로 작성하세요.
                    반드시 Confluence Storage Format(XML)으로만 응답하세요. 코드블록 마커, 설명, 마크다운은 포함하지 마세요.

                    === 서식 예시 (이 XML 구조를 그대로 복제하세요) ===
                    %s

                    === 대상 API 코드 ===
                    %s
                    """),

            Map.entry("db_schema", """
                    당신은 한국어 데이터베이스 문서 작성 전문가입니다.
                    아래 프로젝트 "%s"의 코드를 분석하여 DB 스키마 문서를 한국어로 작성해주세요.

                    작성 요구사항:
                    - 각 테이블명, 컬럼명, 데이터 타입, 제약 조건
                    - 테이블 간 관계 (FK, 1:N 등)
                    - 인덱스 정보 (있는 경우)
                    - 테이블 형식으로 정리

                    중요: 모든 내용을 반드시 한국어로 작성하세요. 테이블명, 컬럼명 등 기술 식별자를 제외한 모든 설명은 한국어여야 합니다.
                    반드시 Confluence Storage Format(XML)으로만 응답하세요. 코드블록 마커(예: xml), 설명, 마크다운은 포함하지 마세요.

                    코드:
                    %s
                    """),

            Map.entry("changelog", """
                    당신은 한국어 기술 문서 작성 전문가입니다.
                    아래 프로젝트 "%s"의 변경 내역을 분석하여 변경 이력 문서를 한국어로 작성해주세요.

                    작성 요구사항:
                    - 변경 일시, 변경자, 변경 내용 요약
                    - 영향 범위 (변경된 모듈/파일)
                    - 테이블 형식으로 정리

                    중요: 모든 내용을 반드시 한국어로 작성하세요.
                    반드시 Confluence Storage Format(XML)으로만 응답하세요. 코드블록 마커(예: xml), 설명, 마크다운은 포함하지 마세요.

                    변경 내역:
                    %s
                    """),

            Map.entry("weekly_report", """
                    당신은 한국어 프로젝트 관리 문서 작성 전문가입니다.
                    아래 프로젝트 "%s"의 Jira 이슈 목록을 분석하여 주간업무 보고서를 한국어로 작성해주세요.

                    작성 요구사항:
                    - 이번 주 완료된 작업 목록
                    - 진행 중인 작업 목록
                    - 다음 주 계획
                    - 이슈/리스크 사항

                    중요: 모든 내용을 반드시 한국어로 작성하세요.
                    반드시 Confluence Storage Format(XML)으로만 응답하세요. 코드블록 마커(예: xml), 설명, 마크다운은 포함하지 마세요.

                    이슈 목록:
                    %s
                    """)
    );

    /**
     * 템플릿에 프로젝트명과 코드를 적용하여 최종 프롬프트를 생성합니다.
     */
    public String getPrompt(String templateName, String code, String projectName) {
        String template = TEMPLATES.get(templateName);
        if (template == null) {
            throw new IllegalArgumentException("지원하지 않는 템플릿: " + templateName);
        }

        String truncatedCode = truncateCode(code);
        return String.format(template, projectName, truncatedCode);
    }

    /**
     * 서식 템플릿 XML을 포함하여 프롬프트를 생성합니다.
     * 서식 템플릿은 truncation 없이 온전히 보존되고, 코드만 truncation이 적용됩니다.
     */
    public String getPromptWithTemplate(String code, String projectName, String templateXml) {
        return getPromptWithTemplate("api_detail_with_template", code, projectName, templateXml);
    }

    public String getPromptWithTemplate(String templateName, String code, String projectName, String templateXml) {
        String template = TEMPLATES.get(templateName);
        if (template == null) {
            throw new IllegalArgumentException("지원하지 않는 템플릿: " + templateName);
        }
        String truncatedCode = truncateCode(code);
        return String.format(template, projectName, templateXml, truncatedCode);
    }

    private String truncateCode(String code) {
        if (code == null) return "";
        if (code.length() <= MAX_CODE_LENGTH) return code;

        log.debug("코드 길이 {}자 → {}자로 잘라냄", code.length(), MAX_CODE_LENGTH);
        return code.substring(0, MAX_CODE_LENGTH) + TRUNCATION_SUFFIX;
    }
}
