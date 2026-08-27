# tfstate 원격 저장소.
#
# backend 블록은 변수를 쓸 수 없다(backend 초기화가 변수 평가보다 먼저다).
# 값을 바꾸려면 variables.tf 의 state_bucket 과 **함께** 고쳐야 한다.
#
# 버킷은 닭-달걀 문제라 Terraform 밖에서 1회 만든다. README 의 부트스트랩 절차 참조.
# 버킷에는 반드시 버저닝을 켠다 — 워크로드와 같은 계정에 있어 계정이 오염되면
# state 도 함께 잃으며, 버저닝이 유일한 복구 수단이다.
#
# use_lockfile 은 Terraform 1.10+ 의 S3 네이티브 잠금이다. DynamoDB 테이블이 필요 없다.
terraform {
  backend "s3" {
    bucket       = "buyorpass-tfstate-251128835262"
    key          = "develop/terraform.tfstate"
    region       = "ap-northeast-2"
    profile      = "root_habin"
    encrypt      = true
    use_lockfile = true
  }
}
