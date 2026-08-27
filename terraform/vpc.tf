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
# ingress 는 80 하나뿐이다. 의도적으로 빠진 것들:
#
#   8080 (앱)   Caddy 가 compose 내부 네트워크로 프록시한다. 외부에 열 이유가 없다.
#   3306 (DB)   compose 내부 전용. ports 매핑조차 하지 않는다.
#   22   (SSH)  SSM Session Manager 로 대체했다(ADR-0012).
#
# 443 은 도메인을 확보한 뒤 Caddy 자동 HTTPS 와 함께 연다.
resource "aws_security_group" "app" {
  name        = "${local.name_prefix}-app"
  description = "Buy or Pass application host"
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

# 기본적으로 만들지 않는다. var.ssh_allowed_cidr 를 지정했을 때만 생성된다.
resource "aws_vpc_security_group_ingress_rule" "ssh" {
  count = var.ssh_allowed_cidr == null ? 0 : 1

  security_group_id = aws_security_group.app.id
  description       = "SSH (emergency only - prefer SSM)"
  cidr_ipv4         = var.ssh_allowed_cidr
  from_port         = 22
  to_port           = 22
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
