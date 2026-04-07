package main

import (
	"os"

	"github.com/aws/aws-cdk-go/awscdk/v2"
	"github.com/aws/jsii-runtime-go"
)

func main() {
	defer jsii.Close()

	app := awscdk.NewApp(nil)

	NewAiDrivenStack(app, "AiDrivenGoStack", &awscdk.StackProps{
		Env: env(),
	})

	app.Synth(nil)
}

func env() *awscdk.Environment {
	account := os.Getenv("CDK_DEFAULT_ACCOUNT")
	region := os.Getenv("CDK_DEFAULT_REGION")
	if account == "" || region == "" {
		return nil
	}
	return &awscdk.Environment{
		Account: jsii.String(account),
		Region:  jsii.String(region),
	}
}
