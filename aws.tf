variable "access_key" {}
variable "secret_key" {}

provider "aws" {
  region = "us-west-1"
  access_key = "${var.access_key}"
  secret_key = "${var.secret_key}"
}

resource "aws_ecs_cluster" "default" {
  name = "edgarly"
}

resource "aws_ecs_service" "zookeeper_service" {
  name            = "zookeeper-service"
  cluster         = "${aws_ecs_cluster.default.id}"
  task_definition = "${aws_ecs_task_definition.zookeeper-task.arn}"
  desired_count   = 1
}
resource "aws_ecs_service" "kafka_service" {
  name            = "kafka-service"
  cluster         = "${aws_ecs_cluster.default.id}"
  task_definition = "${aws_ecs_task_definition.kafka-task.arn}"
  desired_count   = 1
}
resource "aws_ecs_service" "edgarly_service" {
  name            = "edgarly-service"
  cluster         = "${aws_ecs_cluster.default.id}"
  task_definition = "${aws_ecs_task_definition.edgarly-task.arn}"
  desired_count   = 1
}

resource "aws_ecs_task_definition" "zookeeper-task" {
  family = "zookeeper"
  container_definitions = "${file("task-definitions/zookeeper.json")}"
  volume {
    name = "zookeeper-home"
    host_path = "/ecs/zookeeper-home"
  }
}
resource "aws_ecs_task_definition" "kafka-task" {
  family = "kafka"
  container_definitions = "${file("task-definitions/kafka.json")}"
  volume {
    name = "kafka-home"
    host_path = "/ecs/kafka-home"
  }
}
resource "aws_ecs_task_definition" "edgarly-task" {
  family = "edgarly"
  container_definitions = "${file("task-definitions/edgarly.json")}"
  volume {
    name = "edgarly-home"
    host_path = "/ecs/edgarly-home"
  }
}

resource "aws_iam_role" "edgarly" {
    name = "edgarly"
    assume_role_policy = <<EOF
{
  "Version": "2008-10-17",
  "Statement": [
    {
      "Sid": "",
      "Effect": "Allow",
      "Principal": {
        "Service": "ec2.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
EOF
}

resource "aws_iam_policy" "edgarly" {
    name = "edgarly"
    path = "/"
    description = "edgarly"
    policy = <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "ecs:CreateCluster",
        "ecs:DeregisterContainerInstance",
        "ecs:DiscoverPollEndpoint",
        "ecs:Poll",
        "ecs:RegisterContainerInstance",
        "ecs:StartTelemetrySession",
        "ecs:Submit*",
        "ecr:GetAuthorizationToken",
        "ecr:BatchCheckLayerAvailability",
        "ecr:GetDownloadUrlForLayer",
        "ecr:BatchGetImage",
        "logs:CreateLogStream",
        "logs:PutLogEvents"
      ],
      "Resource": "*"
    }
  ]
}
EOF
}

resource "aws_iam_policy_attachment" "edgarly" {
    name = "edgarly"
    roles = ["${aws_iam_role.edgarly.name}"]
    policy_arn = "${aws_iam_policy.edgarly.arn}"
}

resource "aws_iam_instance_profile" "edgarly" {
    name = "edgarly"
    roles = ["${aws_iam_role.edgarly.name}"]
}

resource "aws_security_group" "edgarly" {
  name = "edgarly"
  description = "edgarly"

  ingress {
      from_port = 80
      to_port = 80
      protocol = "tcp"
      cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
      from_port = 22
      to_port = 22
      protocol = "tcp"
      cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
      from_port = 0
      to_port = 0
      protocol = "-1"
      cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_instance" "zookeeper-instance" {
  ami = "ami-bb473cdb"
  instance_type = "d2.xlarge"
  security_groups = ["${aws_security_group.edgarly.name}"]
  iam_instance_profile = "${aws_iam_instance_profile.edgarly.name}"
  user_data = "#!/bin/bash\necho ECS_CLUSTER=edgarly >> /etc/ecs/ecs.config"
  key_name = "aws-timothyjwashington-keypair"
}
resource "aws_instance" "kafka-instance" {
  ami = "ami-bb473cdb"
  instance_type = "t2.small"
  security_groups = ["${aws_security_group.edgarly.name}"]
  iam_instance_profile = "${aws_iam_instance_profile.edgarly.name}"
  user_data = "#!/bin/bash\necho ECS_CLUSTER=edgarly >> /etc/ecs/ecs.config"
  key_name = "aws-timothyjwashington-keypair"
}
resource "aws_instance" "edgarly-instance" {
  ami = "ami-bb473cdb"
  instance_type = "t2.small"
  security_groups = ["${aws_security_group.edgarly.name}"]
  iam_instance_profile = "edgarly"
  user_data = "#!/bin/bash\necho ECS_CLUSTER=edgarly >> /etc/ecs/ecs.config"
  key_name = "aws-timothyjwashington-keypair"
}
