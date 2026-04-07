package spi

import (
	"fmt"
	"sync"
)

// ProviderRegistry manages and resolves SPI providers by name.
type ProviderRegistry struct {
	mu                sync.RWMutex
	sourceControllers map[string]SourceControlProvider
	issueTrackers     map[string]IssueTrackerProvider
	aiProviders       map[string]AiProvider
}

func NewProviderRegistry() *ProviderRegistry {
	return &ProviderRegistry{
		sourceControllers: make(map[string]SourceControlProvider),
		issueTrackers:     make(map[string]IssueTrackerProvider),
		aiProviders:       make(map[string]AiProvider),
	}
}

func (r *ProviderRegistry) RegisterSourceControl(name string, provider SourceControlProvider) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.sourceControllers[name] = provider
}

func (r *ProviderRegistry) RegisterIssueTracker(name string, provider IssueTrackerProvider) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.issueTrackers[name] = provider
}

func (r *ProviderRegistry) RegisterAiProvider(name string, provider AiProvider) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.aiProviders[name] = provider
}

func (r *ProviderRegistry) ResolveSourceControl(name string) (SourceControlProvider, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	p, ok := r.sourceControllers[name]
	return p, ok
}

func (r *ProviderRegistry) ResolveSourceControlByURI(repoURI string) (SourceControlProvider, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	for _, p := range r.sourceControllers {
		if p.Supports(repoURI) {
			return p, true
		}
	}
	return nil, false
}

func (r *ProviderRegistry) ResolveIssueTracker(name string) (IssueTrackerProvider, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	p, ok := r.issueTrackers[name]
	return p, ok
}

func (r *ProviderRegistry) ResolveAiProvider(name string) (AiProvider, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	p, ok := r.aiProviders[name]
	return p, ok
}

func (r *ProviderRegistry) ResolveRequiredSourceControl(name string) (SourceControlProvider, error) {
	p, ok := r.ResolveSourceControl(name)
	if !ok {
		return nil, fmt.Errorf("no source control provider registered for '%s'", name)
	}
	return p, nil
}

func (r *ProviderRegistry) ResolveRequiredIssueTracker(name string) (IssueTrackerProvider, error) {
	p, ok := r.ResolveIssueTracker(name)
	if !ok {
		return nil, fmt.Errorf("no issue tracker provider registered for '%s'", name)
	}
	return p, nil
}
