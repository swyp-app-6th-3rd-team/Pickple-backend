# AL2023 AMI 는 SSM 공개 파라미터로 조회한다.
#
# 이름 필터(al2023-ami-*) 보다 이 방식이 낫다 — 커널 버전을 경로에 고정할 수 있어
# 새 AL2023 릴리스가 나와도 인스턴스가 조용히 replace 되지 않는다.
data "aws_ssm_parameter" "al2023" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-6.1-${var.instance_architecture}"
}

# MySQL 데이터 전용 볼륨.
#
# 이 분리가 ADR-0012 판정 2 의 실체다. root EBS 는 인스턴스 replace 시 함께 삭제되고,
# Terraform 은 AMI·instance_type 변경만으로도 replace 를 일으킨다.
resource "aws_ebs_volume" "data" {
  availability_zone = local.azs[0]
  size              = var.data_volume_size
  type              = "gp3"
  encrypted         = true

  tags = { Name = "${local.name_prefix}-data" }

  # 실수로 날리는 것을 막는다. 정말 지우려면 이 블록을 먼저 지워야 한다.
  # (teardown 시 destroy 가 여기서 멈추는 것은 정상이며, 설계된 방어다.)
  lifecycle {
    prevent_destroy = true
  }
}

# SSH 키페어. 개인키는 AWS 가 생성 시 1회만 반환하므로 로컬 ~/.ssh/pickple-dev.pem 에 보관한다
# (저장소에는 절대 넣지 않는다). AWS 콘솔/CLI 로 먼저 만든 뒤 terraform import 로 상태에 편입했다.
resource "aws_key_pair" "dev" {
  key_name = local.name_prefix

  # ed25519 공개키. 개인키 분실 시 이 리소스를 지우고 새로 발급해야 하며,
  # key_name 이 바뀌면 인스턴스가 replace 된다.
  public_key = var.ssh_public_key

  tags = { Name = "${local.name_prefix}" }

  lifecycle {
    # 공개키 문자열의 주석부(끝의 host 표기)가 달라도 replace 하지 않는다.
    ignore_changes = [public_key]
  }
}

resource "aws_instance" "app" {
  ami           = data.aws_ssm_parameter.al2023.value
  instance_type = var.instance_type

  # ⚠️ key_name 은 launch 시점에만 설정된다 — 값이 바뀌면 인스턴스가 **replace** 된다.
  #    /data 는 별도 EBS(prevent_destroy)라 보존되고, EIP 도 자동 재연결된다.
  #    다만 인스턴스 ID 가 바뀌므로 GitHub vars.EC2_INSTANCE_ID 를 갱신해야 한다(ADR-0023).
  key_name = aws_key_pair.dev.key_name

  subnet_id              = aws_subnet.public[0].id
  vpc_security_group_ids = [aws_security_group.app.id]
  iam_instance_profile   = aws_iam_instance_profile.app.name

  root_block_device {
    volume_size           = var.root_volume_size
    volume_type           = "gp3"
    encrypted             = true
    delete_on_termination = true
  }

  # user_data 를 바꿔도 인스턴스를 replace 하지 않는다(provider 5.x 기본값).
  # 변경은 재부팅 시 반영되며, 대부분의 운영 변경은 SSM 으로 처리한다.
  user_data = templatefile("${path.module}/templates/user-data.sh.tftpl", {
    compose_version  = "2.29.7"
    data_device_name = local.data_device_name
    data_mount_point = local.data_mount_point
    data_volume_id   = aws_ebs_volume.data.id
    ssh_port         = var.ssh_port
    app_dir          = local.app_dir

    fetch_secrets_script = templatefile("${path.module}/templates/fetch-secrets.sh.tftpl", {
      secret_arn        = aws_secretsmanager_secret.app.arn
      region            = var.region
      app_dir           = local.app_dir
      project           = var.project
      mysql_database    = var.project
      mysql_user        = var.project
      ecr_registry      = "${local.account_id}.dkr.ecr.${var.region}.amazonaws.com"
      ecr_repository    = aws_ecr_repository.app.name
      default_image_tag = var.github_deploy_branch
    })
  })

  tags = { Name = "${local.name_prefix}-app" }

  # AMI 가 갱신돼도 자동 replace 되지 않게 한다.
  # AMI 를 올릴 때는 이 줄을 잠시 지우거나 taint 로 의도를 명시한다.
  # (판정 3 의 EBS 영속성 검증은 taint 로 수행한다.)
  lifecycle {
    ignore_changes = [ami]
  }
}

resource "aws_volume_attachment" "data" {
  device_name = local.data_device_name
  volume_id   = aws_ebs_volume.data.id
  instance_id = aws_instance.app.id

  # 인스턴스를 replace 할 때 detach 가 걸리지 않도록 강제 분리를 허용한다.
  # 볼륨 자체는 prevent_destroy 로 보호되므로 데이터는 안전하다.
  force_detach = true
}

# EIP.
#
# 인스턴스가 replace 돼도 주소가 유지된다. 자동 할당 public IP 와 요금이 같으므로
# (둘 다 시간당 IPv4 요금) 붙이지 않을 이유가 없다.
resource "aws_eip" "app" {
  domain   = "vpc"
  instance = aws_instance.app.id

  tags = { Name = "${local.name_prefix}-eip" }

  depends_on = [aws_internet_gateway.main]
}
