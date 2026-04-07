package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"math"
	"os"
	"strings"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/feature/dynamodb/attributevalue"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

// shadowcompare reads agent responses from DynamoDB for both the Java and Go
// services (identified by different tenant prefixes) and compares them.
//
// Usage:
//
//	shadowcompare -table ai-driven-state -since 1h -java-prefix JAVA# -go-prefix GO#

func main() {
	tableName := flag.String("table", "ai-driven-state", "DynamoDB table name")
	since := flag.Duration("since", 24*time.Hour, "Look back duration (e.g. 1h, 24h)")
	javaPrefix := flag.String("java-prefix", "JAVA#", "PK prefix for Java responses")
	goPrefix := flag.String("go-prefix", "GO#", "PK prefix for Go responses")
	region := flag.String("region", "", "AWS region (defaults to AWS_REGION env)")
	flag.Parse()

	ctx := context.Background()

	opts := []func(*awsconfig.LoadOptions) error{}
	if *region != "" {
		opts = append(opts, awsconfig.WithRegion(*region))
	}
	cfg, err := awsconfig.LoadDefaultConfig(ctx, opts...)
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to load AWS config: %v\n", err)
		os.Exit(1)
	}

	client := dynamodb.NewFromConfig(cfg)
	cutoff := time.Now().Add(-*since)

	javaResponses, err := queryResponses(ctx, client, *tableName, *javaPrefix, cutoff)
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to query Java responses: %v\n", err)
		os.Exit(1)
	}

	goResponses, err := queryResponses(ctx, client, *tableName, *goPrefix, cutoff)
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to query Go responses: %v\n", err)
		os.Exit(1)
	}

	report := compare(javaResponses, goResponses)
	report.Print()

	if report.Failures > 0 {
		os.Exit(1)
	}
}

type agentResponse struct {
	TicketKey  string   `dynamodbav:"ticketKey"`
	Text       string   `dynamodbav:"text"`
	ToolsUsed  []string `dynamodbav:"toolsUsed"`
	TokenCount int      `dynamodbav:"tokenCount"`
	TurnCount  int      `dynamodbav:"turnCount"`
	Timestamp  string   `dynamodbav:"timestamp"`
}

func queryResponses(ctx context.Context, client *dynamodb.Client, tableName, pkPrefix string, since time.Time) (map[string]agentResponse, error) {
	results := make(map[string]agentResponse)

	input := &dynamodb.QueryInput{
		TableName:              aws.String(tableName),
		IndexName:              aws.String("GSI1"),
		KeyConditionExpression: aws.String("GSI1PK = :pk AND GSI1SK >= :since"),
		ExpressionAttributeValues: map[string]types.AttributeValue{
			":pk":    &types.AttributeValueMemberS{Value: pkPrefix + "SHADOW_RESPONSE"},
			":since": &types.AttributeValueMemberS{Value: since.Format(time.RFC3339)},
		},
	}

	paginator := dynamodb.NewQueryPaginator(client, input)
	for paginator.HasMorePages() {
		page, err := paginator.NextPage(ctx)
		if err != nil {
			return nil, fmt.Errorf("query page: %w", err)
		}

		for _, item := range page.Items {
			var resp agentResponse
			if err := attributevalue.UnmarshalMap(item, &resp); err != nil {
				continue
			}
			results[resp.TicketKey] = resp
		}
	}
	return results, nil
}

type comparisonReport struct {
	Total      int
	Matches    int
	Failures   int
	JavaOnly   int
	GoOnly     int
	Details    []comparisonDetail
	TokenDrift float64
}

type comparisonDetail struct {
	TicketKey   string
	Status      string
	JavaTokens  int
	GoTokens    int
	JavaTools   string
	GoTools     string
	TextSimilar bool
}

func compare(java, goResp map[string]agentResponse) comparisonReport {
	report := comparisonReport{}
	seen := make(map[string]bool)

	var totalJavaTokens, totalGoTokens int

	for key, jResp := range java {
		seen[key] = true
		report.Total++

		gResp, exists := goResp[key]
		if !exists {
			report.JavaOnly++
			report.Details = append(report.Details, comparisonDetail{
				TicketKey: key,
				Status:    "JAVA_ONLY",
			})
			continue
		}

		totalJavaTokens += jResp.TokenCount
		totalGoTokens += gResp.TokenCount

		toolsMatch := sameTools(jResp.ToolsUsed, gResp.ToolsUsed)
		textSimilar := cosineSimilar(jResp.Text, gResp.Text)
		tokenDelta := math.Abs(float64(jResp.TokenCount-gResp.TokenCount)) / math.Max(float64(jResp.TokenCount), 1)

		detail := comparisonDetail{
			TicketKey:   key,
			JavaTokens:  jResp.TokenCount,
			GoTokens:    gResp.TokenCount,
			JavaTools:   strings.Join(jResp.ToolsUsed, ","),
			GoTools:     strings.Join(gResp.ToolsUsed, ","),
			TextSimilar: textSimilar,
		}

		if toolsMatch && textSimilar && tokenDelta < 0.2 {
			detail.Status = "MATCH"
			report.Matches++
		} else {
			detail.Status = "MISMATCH"
			report.Failures++
		}
		report.Details = append(report.Details, detail)
	}

	for key := range goResp {
		if !seen[key] {
			report.Total++
			report.GoOnly++
			report.Details = append(report.Details, comparisonDetail{
				TicketKey: key,
				Status:    "GO_ONLY",
			})
		}
	}

	if totalJavaTokens > 0 {
		report.TokenDrift = float64(totalGoTokens-totalJavaTokens) / float64(totalJavaTokens) * 100
	}

	return report
}

func sameTools(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	set := make(map[string]bool, len(a))
	for _, t := range a {
		set[t] = true
	}
	for _, t := range b {
		if !set[t] {
			return false
		}
	}
	return true
}

// cosineSimilar does a simple word-overlap similarity check.
// Returns true if >70% of words overlap (good enough for shadow comparison).
func cosineSimilar(a, b string) bool {
	wordsA := strings.Fields(strings.ToLower(a))
	wordsB := strings.Fields(strings.ToLower(b))

	if len(wordsA) == 0 && len(wordsB) == 0 {
		return true
	}
	if len(wordsA) == 0 || len(wordsB) == 0 {
		return false
	}

	setA := make(map[string]int, len(wordsA))
	for _, w := range wordsA {
		setA[w]++
	}
	setB := make(map[string]int, len(wordsB))
	for _, w := range wordsB {
		setB[w]++
	}

	var overlap int
	for w, countA := range setA {
		if countB, ok := setB[w]; ok {
			if countA < countB {
				overlap += countA
			} else {
				overlap += countB
			}
		}
	}

	total := len(wordsA)
	if len(wordsB) > total {
		total = len(wordsB)
	}

	return float64(overlap)/float64(total) > 0.7
}

func (r comparisonReport) Print() {
	fmt.Println("=== Shadow Mode Comparison Report ===")
	fmt.Printf("Total:    %d\n", r.Total)
	fmt.Printf("Matches:  %d\n", r.Matches)
	fmt.Printf("Failures: %d\n", r.Failures)
	fmt.Printf("Java-only:%d\n", r.JavaOnly)
	fmt.Printf("Go-only:  %d\n", r.GoOnly)
	fmt.Printf("Token drift: %.1f%%\n\n", r.TokenDrift)

	if len(r.Details) == 0 {
		fmt.Println("No responses to compare.")
		return
	}

	fmt.Printf("%-15s %-10s %-10s %-10s %-8s %-30s %-30s\n",
		"Ticket", "Status", "Java Tok", "Go Tok", "TextSim", "Java Tools", "Go Tools")
	fmt.Println(strings.Repeat("-", 113))

	for _, d := range r.Details {
		textSim := "n/a"
		if d.Status != "JAVA_ONLY" && d.Status != "GO_ONLY" {
			if d.TextSimilar {
				textSim = "yes"
			} else {
				textSim = "NO"
			}
		}
		fmt.Printf("%-15s %-10s %-10d %-10d %-8s %-30s %-30s\n",
			d.TicketKey, d.Status, d.JavaTokens, d.GoTokens, textSim,
			truncate(d.JavaTools, 30), truncate(d.GoTools, 30))
	}
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n-3] + "..."
}

// shadowRecord is the format both Java and Go services should write to DynamoDB
// when running in shadow mode.
type shadowRecord struct {
	PK         string   `dynamodbav:"PK" json:"PK"`
	SK         string   `dynamodbav:"SK" json:"SK"`
	GSI1PK     string   `dynamodbav:"GSI1PK" json:"GSI1PK"`
	GSI1SK     string   `dynamodbav:"GSI1SK" json:"GSI1SK"`
	TicketKey  string   `dynamodbav:"ticketKey" json:"ticketKey"`
	Text       string   `dynamodbav:"text" json:"text"`
	ToolsUsed  []string `dynamodbav:"toolsUsed" json:"toolsUsed"`
	TokenCount int      `dynamodbav:"tokenCount" json:"tokenCount"`
	TurnCount  int      `dynamodbav:"turnCount" json:"turnCount"`
	Timestamp  string   `dynamodbav:"timestamp" json:"timestamp"`
	TTL        int64    `dynamodbav:"ttl" json:"ttl"`
}

// ExampleShadowRecord shows how to build a shadow record for DynamoDB.
// Both Java and Go services call this after processing a webhook.
func ExampleShadowRecord() {
	prefix := "GO#" // or "JAVA#"
	ticketKey := "PROJ-123"
	now := time.Now()

	record := shadowRecord{
		PK:         fmt.Sprintf("SHADOW#%s%s", prefix, ticketKey),
		SK:         now.Format(time.RFC3339),
		GSI1PK:     prefix + "SHADOW_RESPONSE",
		GSI1SK:     now.Format(time.RFC3339),
		TicketKey:  ticketKey,
		Text:       "response text...",
		ToolsUsed:  []string{"jira_get_ticket", "github_search_files"},
		TokenCount: 1500,
		TurnCount:  3,
		Timestamp:  now.Format(time.RFC3339),
		TTL:        now.Add(14 * 24 * time.Hour).Unix(),
	}

	b, err := json.MarshalIndent(record, "", "  ")
	if err != nil {
		fmt.Fprintf(os.Stderr, "marshal error: %v\n", err)
		return
	}
	fmt.Println(string(b))
}
