package sqslistener

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	sqstypes "github.com/aws/aws-sdk-go-v2/service/sqs/types"
	"github.com/rs/zerolog/log"

	"github.com/AirdropToTheMoon/ai-driven/internal/agent/model"
	"github.com/AirdropToTheMoon/ai-driven/internal/http/handler"
	"github.com/AirdropToTheMoon/ai-driven/internal/spi"
)

// SQSReceiver abstracts the SQS receive and delete operations.
type SQSReceiver interface {
	ReceiveMessage(ctx context.Context, params *sqs.ReceiveMessageInput, optFns ...func(*sqs.Options)) (*sqs.ReceiveMessageOutput, error)
	DeleteMessage(ctx context.Context, params *sqs.DeleteMessageInput, optFns ...func(*sqs.Options)) (*sqs.DeleteMessageOutput, error)
}

// AgentProcessor processes agent requests.
type AgentProcessor interface {
	Process(ctx context.Context, request *model.AgentRequest, intent model.CommentIntent) (*model.AgentResponse, error)
}

// Listener polls SQS FIFO for agent tasks and processes them.
type Listener struct {
	sqsClient  SQSReceiver
	queueURL   string
	processor  AgentProcessor
	jiraClient spi.IssueTrackerProvider
	stopCh     chan struct{}
	wg         sync.WaitGroup
}

// NewListener creates a new SQS listener.
func NewListener(sqsClient SQSReceiver, queueURL string, processor AgentProcessor, jiraClient spi.IssueTrackerProvider) *Listener {
	return &Listener{
		sqsClient:  sqsClient,
		queueURL:   queueURL,
		processor:  processor,
		jiraClient: jiraClient,
		stopCh:     make(chan struct{}),
	}
}

// Start launches the SQS polling goroutine.
func (l *Listener) Start(ctx context.Context) {
	l.wg.Add(1)
	go l.poll(ctx)
}

// Stop signals the polling goroutine to stop and waits for completion.
func (l *Listener) Stop() {
	close(l.stopCh)
	l.wg.Wait()
}

func (l *Listener) poll(ctx context.Context) {
	defer l.wg.Done()

	logger := log.With().Str("component", "sqslistener").Logger()
	logger.Info().Str("queueURL", l.queueURL).Msg("SQS listener started")

	for {
		select {
		case <-ctx.Done():
			logger.Info().Msg("SQS listener stopping (context cancelled)")
			return
		case <-l.stopCh:
			logger.Info().Msg("SQS listener stopping (stop signal)")
			return
		default:
		}

		output, err := l.sqsClient.ReceiveMessage(ctx, &sqs.ReceiveMessageInput{
			QueueUrl:            aws.String(l.queueURL),
			MaxNumberOfMessages: 1,
			WaitTimeSeconds:     20,
		})
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			logger.Error().Err(err).Msg("Failed to receive SQS message")
			continue
		}

		for i := range output.Messages {
			l.processMessage(ctx, &output.Messages[i])
		}
	}
}

func (l *Listener) processMessage(ctx context.Context, msg *sqstypes.Message) {
	body := aws.ToString(msg.Body)
	receiptHandle := aws.ToString(msg.ReceiptHandle)
	l.ProcessSQSMessage(ctx, body, receiptHandle)
}

// ProcessSQSMessage processes a single SQS message by body and receipt handle.
func (l *Listener) ProcessSQSMessage(ctx context.Context, body, receiptHandle string) {
	logger := log.Ctx(ctx).With().Str("component", "sqslistener").Logger()

	var task handler.AgentTask
	if err := json.Unmarshal([]byte(body), &task); err != nil {
		logger.Error().Err(err).Msg("Failed to unmarshal agent task")
		return
	}

	logger = logger.With().
		Str("ticketKey", task.TicketKey).
		Str("platform", task.Platform).
		Str("correlationId", task.CorrelationID).
		Logger()

	opCtx := spi.NewOperationContext(task.TenantID)
	opCtx.CorrelationID = task.CorrelationID
	opCtx.TicketKey = spi.NewTicketKeyOrNil(task.TicketKey)

	request := &model.AgentRequest{
		TicketKey:     task.TicketKey,
		Platform:      task.Platform,
		CommentBody:   task.CommentBody,
		CommentAuthor: task.CommentAuthor,
		AckCommentID:  task.AckCommentID,
		Context:       opCtx,
		PRContext:     task.PRContext,
	}

	intent := model.CommentIntent(task.Intent)
	logger.Info().Str("intent", task.Intent).Msg("Processing agent task")

	response, err := l.processor.Process(ctx, request, intent)
	if err != nil {
		logger.Error().Err(err).Msg("Agent processing failed")
		l.postErrorComment(ctx, &opCtx, task.TicketKey)
		return
	}

	if l.jiraClient != nil && response.Text != "" {
		if err := l.jiraClient.PostComment(ctx, &opCtx, task.TicketKey, response.Text); err != nil {
			logger.Error().Err(err).Msg("Failed to post response comment")
			return
		}
	}

	l.deleteMessage(ctx, receiptHandle)
	logger.Info().
		Int("tokenCount", response.TokenCount).
		Int("turnCount", response.TurnCount).
		Msg("Agent task completed successfully")
}

func (l *Listener) postErrorComment(ctx context.Context, opCtx *spi.OperationContext, ticketKey string) {
	if l.jiraClient == nil {
		return
	}
	errComment := fmt.Sprintf("An error occurred while processing your request for %s. The system will retry automatically.", ticketKey)
	if err := l.jiraClient.PostComment(ctx, opCtx, ticketKey, errComment); err != nil {
		log.Ctx(ctx).Error().Err(err).Msg("Failed to post error comment")
	}
}

func (l *Listener) deleteMessage(ctx context.Context, receiptHandle string) {
	_, err := l.sqsClient.DeleteMessage(ctx, &sqs.DeleteMessageInput{
		QueueUrl:      aws.String(l.queueURL),
		ReceiptHandle: aws.String(receiptHandle),
	})
	if err != nil {
		log.Ctx(ctx).Error().Err(err).Msg("Failed to delete SQS message")
	}
}
