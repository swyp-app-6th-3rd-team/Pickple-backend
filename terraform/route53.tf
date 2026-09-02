# DNS (ADR-0022).
#
# pickple.app 은 Gabia 에 등록돼 있고 네임서버만 Route53 으로 위임한다.
# apply 뒤 `terraform output route53_name_servers` 의 4개를 Gabia "네임서버 설정" 에 넣는 것이
# 이 파일과 관련된 유일한 수동 단계다. 그 뒤 레코드는 전부 여기서 관리한다.
#
# 비용: hosted zone $0.50/월. 레코드·쿼리는 이 규모에서 사실상 $0.

resource "aws_route53_zone" "main" {
  name    = var.domain_name
  comment = "${local.name_prefix} (ADR-0022)"

  # 6주 뒤 teardown 시 레코드가 남아 있어도 zone 을 지울 수 있게 한다.
  # zone 은 프로젝트가 끝나도 과금되므로 prevent_destroy 를 두지 않는다.
  force_destroy = true

  tags = { Name = "${local.name_prefix}-zone" }
}

# 백엔드 develop 서버. EIP 에 직접 바인딩되므로 인스턴스를 교체해도 레코드는 그대로다.
# AAAA 는 만들지 않는다 — 인스턴스에 IPv6 를 할당하지 않았다.
resource "aws_route53_record" "api" {
  zone_id = aws_route53_zone.main.zone_id
  name    = local.api_fqdn
  type    = "A"
  ttl     = 300
  records = [aws_eip.app.public_ip]
}

# 프론트 등 다른 팀의 레코드. NS 가 Route53 으로 넘어오면 apex·www 도 여기서 관리해야 한다.
# tfvars 에 한 줄 추가하는 것으로 받는다(변수 설명 참조).
resource "aws_route53_record" "extra" {
  for_each = var.extra_records

  zone_id = aws_route53_zone.main.zone_id
  name    = each.key == "@" ? var.domain_name : "${each.key}.${var.domain_name}"
  type    = each.value.type
  ttl     = each.value.ttl
  records = each.value.records
}
