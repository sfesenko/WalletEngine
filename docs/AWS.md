# How to host WalletEngine on AWS

- Local: `compose.yaml` — Postgres/Redis/RabbitMQ by default (app on host); `--profile app` runs the app + one-shot seed
- AWS: same topology, managed backing services, app image from `src/main/docker/Dockerfile` (built once by `scripts/build-image.sh`, pushed to ECR; the same image compose runs)
- Infra as code: Terraform / CloudFormation / Pulumi

## Possible options for hosting
1. Managed backing + ECS for app
2. Managed backing + EKS app
3. All self-hosted in K8s
4. AWS EC2 + docker compose (+docker swarm)

While EKS provides more flexibility, for this app it looks like overkill, so 1st approach (Managed backing + ECS for app) seems to be the most pragmatic one. 
Docker Swarm offers "natural" way of deploying `compose.yaml` to AWS, but at cost of no integration with AWS - just way to "run docker compose in AWS"

## Managed backing + ECS Fargate
This requires creating new (sub) project with Terraform / CloudFormation / Pulumi, and describing required infrastructure. 
(Well, it's still possible to do everything manually via AWS console, but that is not recommended)

- Network: 
    - VPC, private subnets for tasks + public subnets
    - NAT for ECR pull (or ECR VPC endpoints); 
    - gRPC `9090` via NLB TCP pass-through
- Health: ECS `healthCheck` 
- CloudWatch for logs/metrics
- Secrets: RDS/Redis/MQ endpoints + creds via Secrets Manager/Parameter Store
- Postgres: RDS Aurora PostgreSQL
- Redis: ElastiCache
- Messaging: Amazon MQ for RabbitMQ
- App: ECR image, ECS Fargate service

- CI/CD: needs AWS integration:
  - push -> CI builds image -> ECR -> `aws ecs update-service --force-new-deployment`

### DB Update
While current FlyWay integration will work "out of the box", may consider to extract it into separate project (command line app) that will be need to run before deployment to update DB. 
This approach allows more "DBA-friendly" way to manage DB changes