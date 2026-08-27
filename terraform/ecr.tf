# 컨테이너 레지스트리.
#
# 참조한 실서비스 스택은 ECR 을 data 로만 참조하고 lifecycle policy 가 없어
# 빌드마다 쌓이는 불변 태그 이미지가 무한 누적된다. 여기서는 그 부분을 고친다.

resource "aws_ecr_repository" "app" {
  name = var.project

  # 배포가 불변 태그(develop-<run_id>)를 참조하므로 MUTABLE 이어야 한다 —
  # 이동 태그(develop)를 매 빌드마다 다시 붙이기 때문이다.
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256" # KMS 는 요청당 과금이라 develop 에는 과하다
  }

  # 6주 후 teardown 시 이미지가 남아 있어도 지울 수 있게 한다.
  force_delete = true

  tags = { Name = "${local.name_prefix}-ecr" }
}

# 빌드마다 develop-<run_id> 가 하나씩 쌓인다. 정리하지 않으면 계속 늘어난다.
resource "aws_ecr_lifecycle_policy" "app" {
  repository = aws_ecr_repository.app.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "태그 없는 이미지는 1일 후 삭제"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 1
        }
        action = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "develop 태그 이미지는 최근 ${var.ecr_keep_image_count}개만 유지 (롤백 여유)"
        selection = {
          tagStatus     = "tagged"
          tagPrefixList = [var.github_deploy_branch]
          countType     = "imageCountMoreThan"
          countNumber   = var.ecr_keep_image_count
        }
        action = { type = "expire" }
      },
    ]
  })
}
