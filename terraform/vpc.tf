# 네트워크.
#
# 여기 있는 리소스는 **전부 무료**다(VPC·Subnet·IGW·Route Table·SG).
# 과금은 NAT Gateway·Interface VPC Endpoint 같은 관리형 박스에서만 발생하며,
# 이 구성에는 그것이 하나도 없다(ADR-0012).

resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = "${local.name_prefix}-vpc" }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = { Name = "${local.name_prefix}-igw" }
}

# public subnet 2개.
# EC2 는 [0] 에만 배치한다. [1] 은 나중에 ALB·RDS 를 붙일 때를 위한 자리로,
# subnet 은 무료이고 뒤늦게 추가하려면 파괴적 변경이 되기 때문에 미리 만든다.
resource "aws_subnet" "public" {
  count = length(var.public_subnet_cidrs)

  vpc_id                  = aws_vpc.main.id
  cidr_block              = var.public_subnet_cidrs[count.index]
  availability_zone       = local.azs[count.index]
  map_public_ip_on_launch = true

  tags = {
    Name = "${local.name_prefix}-public-${local.azs[count.index]}"
    Tier = "public"
  }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  route {
    ipv6_cidr_block = "::/0"
    gateway_id      = aws_internet_gateway.main.id
  }

  tags = { Name = "${local.name_prefix}-public-rt" }
}

resource "aws_route_table_association" "public" {
  count = length(aws_subnet.public)

  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# ── Security Group ─────────────────────────────────────────
#
# 상시 개방은 80·443 둘뿐이다.
#
#   8080 (앱)   여전히 닫혀 있다. Caddy 가 compose 내부 네트워크로 프록시한다.
#
# 443 은 Caddy 가 Let's Encrypt 로 종단한다(ADR-0022). 80 은 ACME HTTP-01 과
# 80→443 리다이렉트를 위해 계속 연다.
#
# 아래 둘은 변수를 지정했을 때만 생성된다(기본 null = 규칙 없음). ADR-0023 에서
# ADR-0012 의 "SSH 를 열지 않는다" 결정을 이 두 경로에 한해 대체했다.
#
#   var.ssh_port        (기본 22, 운영은 124)    var.ssh_allowed_cidr 지정 시
#   var.mysql_host_port (기본 13307 → 컨테이너 3306) var.mysql_allowed_cidr 지정 시
#
# ⚠️ MySQL 을 열면 DB 가 인터넷에 노출된다. 방어선은 계정 비밀번호뿐이므로
#    remote root 를 막고 앱 계정으로만 붙는다. 되돌리려면 변수를 null 로 두고 apply 한다
#    (규칙이 count=0 으로 사라진다 — 인스턴스 replace 없음).
resource "aws_security_group" "app" {
  name        = "${local.name_prefix}-app"
  description = "Pickple application host"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${local.name_prefix}-app" }
}

resource "aws_vpc_security_group_ingress_rule" "http_ipv4" {
  security_group_id = aws_security_group.app.id
  description       = "HTTP"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = local.http_port
  to_port           = local.http_port
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "http_ipv6" {
  security_group_id = aws_security_group.app.id
  description       = "HTTP (IPv6)"
  cidr_ipv6         = "::/0"
  from_port         = local.http_port
  to_port           = local.http_port
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "https_ipv4" {
  security_group_id = aws_security_group.app.id
  description       = "HTTPS"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = local.https_port
  to_port           = local.https_port
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "https_ipv6" {
  security_group_id = aws_security_group.app.id
  description       = "HTTPS (IPv6)"
  cidr_ipv6         = "::/0"
  from_port         = local.https_port
  to_port           = local.https_port
  ip_protocol       = "tcp"
}

# 기본적으로 만들지 않는다. var.ssh_allowed_cidr 를 지정했을 때만 생성된다.
resource "aws_vpc_security_group_ingress_rule" "ssh" {
  count = var.ssh_allowed_cidr == null ? 0 : 1

  security_group_id = aws_security_group.app.id
  description       = "SSH"
  cidr_ipv4         = var.ssh_allowed_cidr
  from_port         = var.ssh_port
  to_port           = var.ssh_port
  ip_protocol       = "tcp"
}

# MySQL. 컨테이너 3306 을 호스트 var.mysql_host_port 로 매핑한 것을 연다
# (docker-compose-ec2.yml 의 ports 와 값이 같아야 한다).
resource "aws_vpc_security_group_ingress_rule" "mysql" {
  count = var.mysql_allowed_cidr == null ? 0 : 1

  security_group_id = aws_security_group.app.id
  description       = "MySQL (external client access)"
  cidr_ipv4         = var.mysql_allowed_cidr
  from_port         = var.mysql_host_port
  to_port           = var.mysql_host_port
  ip_protocol       = "tcp"
}

# 아웃바운드는 열어둔다. ECR pull·Secrets Manager·SSM 이 모두 IGW 를 통해 나간다.
# 이것이 NAT Gateway 를 대체하는 경로다.
resource "aws_vpc_security_group_egress_rule" "all_ipv4" {
  security_group_id = aws_security_group.app.id
  description       = "All outbound"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

resource "aws_vpc_security_group_egress_rule" "all_ipv6" {
  security_group_id = aws_security_group.app.id
  description       = "All outbound (IPv6)"
  cidr_ipv6         = "::/0"
  ip_protocol       = "-1"
}
