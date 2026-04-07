package context

import (
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestNewFileSummarizer_DefaultThreshold(t *testing.T) {
	s := NewFileSummarizer(0)
	assert.Equal(t, DefaultThreshold, s.Threshold())
}

func TestNewFileSummarizer_NegativeThreshold(t *testing.T) {
	s := NewFileSummarizer(-10)
	assert.Equal(t, DefaultThreshold, s.Threshold())
}

func TestNewFileSummarizer_CustomThreshold(t *testing.T) {
	s := NewFileSummarizer(1000)
	assert.Equal(t, 1000, s.Threshold())
}

func TestSummarize_BelowThreshold_ReturnsUnchanged(t *testing.T) {
	s := NewFileSummarizer(5000)
	content := "package main\nfunc main() {}"
	result := s.Summarize(content, ".go")
	assert.Equal(t, content, result)
}

func TestSummarize_Java(t *testing.T) {
	content := strings.Repeat("// padding\n", 100) + `package com.example.app;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Transactional
public class UserService extends BaseService implements Serializable {

    private final UserRepository userRepository;
    public static final String DEFAULT_ROLE = "user";

    public UserService(UserRepository repo) {
        this.userRepository = repo;
    }

    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }

    private void validate(User user) throws ValidationException {
        if (user == null) {
            throw new ValidationException("user is null");
        }
    }
}`
	s := NewFileSummarizer(100)
	result := s.Summarize(content, ".java")

	assert.Contains(t, result, "[SUMMARIZED")
	assert.Contains(t, result, "package com.example.app;")
	assert.Contains(t, result, "import java.util.List;")
	assert.Contains(t, result, "import java.util.Map;")
	assert.Contains(t, result, "@Service")
	assert.Contains(t, result, "public class UserService")
	assert.Contains(t, result, "{ ... }")
	// Should not contain full method bodies
	assert.NotContains(t, result, "userRepository.findById")
}

func TestSummarize_Java_ManyImports(t *testing.T) {
	var imports strings.Builder
	for i := 0; i < 25; i++ {
		imports.WriteString("import java.util.List" + strings.Repeat("x", i) + ";\n")
	}
	content := strings.Repeat("// padding\n", 100) + "package test;\n\n" + imports.String() + "\npublic class Test {}\n"
	s := NewFileSummarizer(100)
	result := s.Summarize(content, "java")

	assert.Contains(t, result, "and 5 more imports")
}

func TestSummarize_TypeScript(t *testing.T) {
	content := strings.Repeat("// padding\n", 100) + `import { Component } from '@angular/core';
import { HttpClient } from '@angular/common/http';

export interface UserDTO {
  id: number;
  name: string;
}

export type Status = 'active' | 'inactive';

export class UserService {
  constructor(private http: HttpClient) {}

  getUsers(): Observable<UserDTO[]> {
    return this.http.get('/api/users');
  }
}

export async function fetchData(url: string): Promise<Response> {
  return await fetch(url);
}

export const processItems = (items: string[]) => {
  return items.map(i => i.trim());
};
`
	s := NewFileSummarizer(100)
	result := s.Summarize(content, ".ts")

	assert.Contains(t, result, "[SUMMARIZED")
	assert.Contains(t, result, "import { Component }")
	assert.Contains(t, result, "export interface UserDTO")
	assert.Contains(t, result, "export type Status")
	assert.Contains(t, result, "export class UserService")
	assert.Contains(t, result, "export async function fetchData")
}

func TestSummarize_TypeScript_ManyImports(t *testing.T) {
	var imports strings.Builder
	for i := 0; i < 20; i++ {
		imports.WriteString("import { Thing" + strings.Repeat("x", i) + " } from 'module';\n")
	}
	content := strings.Repeat("// padding\n", 100) + imports.String() + "\nexport class Foo {}\n"
	s := NewFileSummarizer(100)
	result := s.Summarize(content, ".tsx")

	assert.Contains(t, result, "and 5 more imports")
}

func TestSummarize_Python(t *testing.T) {
	content := strings.Repeat("# padding\n", 100) + `import os
from typing import List, Optional

class UserService:
    """Service for user operations."""

    def __init__(self, repo):
        self.repo = repo

    def find_by_id(self, user_id: int) -> Optional[dict]:
        """Find a user by their ID."""
        return self.repo.get(user_id)

    async def fetch_users(self) -> List[dict]:
        """Fetch all users."""
        return await self.repo.list()

def helper_function(data: str) -> bool:
    """Check if data is valid."""
    return len(data) > 0
`
	s := NewFileSummarizer(100)
	result := s.Summarize(content, ".py")

	assert.Contains(t, result, "[SUMMARIZED")
	assert.Contains(t, result, "import os")
	assert.Contains(t, result, "from typing import List")
	assert.Contains(t, result, "class UserService:")
	assert.Contains(t, result, "def find_by_id")
	assert.Contains(t, result, "async def fetch_users")
	assert.Contains(t, result, "def helper_function")
}

func TestSummarize_Go(t *testing.T) {
	content := strings.Repeat("// padding\n", 100) + `package service

import (
	"context"
	"fmt"
)

type UserService struct {
	repo UserRepository
}

type UserRepository interface {
	FindByID(ctx context.Context, id string) (*User, error)
}

type Status int

func NewUserService(repo UserRepository) *UserService {
	return &UserService{repo: repo}
}

func (s *UserService) GetUser(ctx context.Context, id string) (*User, error) {
	user, err := s.repo.FindByID(ctx, id)
	if err != nil {
		return nil, fmt.Errorf("find user: %w", err)
	}
	return user, nil
}
`
	s := NewFileSummarizer(100)
	result := s.Summarize(content, ".go")

	assert.Contains(t, result, "[SUMMARIZED")
	assert.Contains(t, result, "package service")
	assert.Contains(t, result, `"context"`)
	assert.Contains(t, result, `"fmt"`)
	assert.Contains(t, result, "type UserService struct")
	assert.Contains(t, result, "type UserRepository interface")
	assert.Contains(t, result, "func NewUserService(repo UserRepository) *UserService")
	assert.Contains(t, result, "func (s *UserService) GetUser(ctx context.Context, id string) (*User, error)")
	// Should not contain method bodies
	assert.NotContains(t, result, "s.repo.FindByID")
}

func TestSummarize_UnsupportedExtension(t *testing.T) {
	content := strings.Repeat("x", 3000)
	s := NewFileSummarizer(100)
	result := s.Summarize(content, ".csv")

	assert.Contains(t, result, "TRUNCATED")
	assert.Contains(t, result, ".csv")
	assert.Contains(t, result, "not supported")
	// Should be truncated
	require.Less(t, len(result), len(content))
}

func TestSummarize_UnsupportedExtension_Short(t *testing.T) {
	content := "short content"
	s := NewFileSummarizer(5) // very low threshold to trigger summarization
	result := s.Summarize(content, ".bin")

	assert.Contains(t, result, "TRUNCATED")
	assert.Contains(t, result, "short content")
}

func TestSummarize_ExtensionNormalization(t *testing.T) {
	content := strings.Repeat("// padding\n", 100) + "package main\n\nfunc main() {}\n"
	s := NewFileSummarizer(100)

	// With dot prefix
	r1 := s.Summarize(content, ".go")
	// Without dot prefix
	r2 := s.Summarize(content, "go")
	// Uppercase
	r3 := s.Summarize(content, ".GO")

	assert.Contains(t, r1, "package main")
	assert.Contains(t, r2, "package main")
	assert.Contains(t, r3, "package main")
}

func TestSummarize_JSX(t *testing.T) {
	content := strings.Repeat("// padding\n", 100) + `import React from 'react';
import { useState } from 'react';

interface Props {
  name: string;
}

export function Greeting(props: Props) {
  const [count, setCount] = useState(0);
  return <div>Hello {props.name}</div>;
}
`
	s := NewFileSummarizer(100)
	result := s.Summarize(content, ".jsx")

	assert.Contains(t, result, "[SUMMARIZED")
	assert.Contains(t, result, "import React")
}
