# 런타임 비밀.
#
# Terraform 은 **그릇만 만들고 값은 소유하지 않는다**(ADR-0013).
# random_password 로 생성하면 편하지만 tfstate 에 평문으로 남는다.

resource "aws_secretsmanager_secret" "app" {
  name        = "${local.name_prefix}/app"
  description = "Buy or Pass ${var.env} runtime secrets"

  # 기본값은 30일 유예 삭제다. 6주 프로젝트에서 teardown 후 같은 이름으로 다시 만들면
  # "삭제 예정인 이름과 충돌"로 실패하므로 즉시 삭제로 둔다.
  recovery_window_in_days = 0

  tags = { Name = "${local.name_prefix}-app-secret" }
}

# 초기값은 전부 자리표시자다.
# 실제 값은 apply 후 CLI 로 1회 주입한다(README 참조).
#
# ignore_changes 덕분에 이후 Terraform 은 값을 건드리지 않는다 —
# 즉 실값이 tfstate 에 들어가지 않는다.
resource "aws_secretsmanager_secret_version" "app" {
  secret_id = aws_secretsmanager_secret.app.id

  secret_string = jsonencode({
    for key in local.secret_keys : key => "CHANGE_ME"
  })

  lifecycle {
    ignore_changes = [secret_string]
  }
}
