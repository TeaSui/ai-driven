import { ValidationError } from './errors';

export interface ValidationRule<T> {
  field: keyof T;
  rules: Array<(value: unknown) => string | null>;
}

export function required(value: unknown): string | null {
  if (value === null || value === undefined || value === '') {
    return 'This field is required';
  }
  return null;
}

export function isEmail(value: unknown): string | null {
  if (typeof value !== 'string') return 'Must be a string';
  const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
  if (!emailRegex.test(value)) return 'Must be a valid email address';
  return null;
}

export function minLength(min: number) {
  return (value: unknown): string | null => {
    if (typeof value !== 'string') return 'Must be a string';
    if (value.length < min) return `Must be at least ${min} characters long`;
    return null;
  };
}

export function maxLength(max: number) {
  return (value: unknown): string | null => {
    if (typeof value !== 'string') return 'Must be a string';
    if (value.length > max) return `Must be at most ${max} characters long`;
    return null;
  };
}

export function isEnum<T extends object>(enumObj: T) {
  return (value: unknown): string | null => {
    const validValues = Object.values(enumObj);
    if (!validValues.includes(value as T[keyof T])) {
      return `Must be one of: ${validValues.join(', ')}`;
    }
    return null;
  };
}

export function validate<T extends object>(
  data: Partial<T>,
  rules: ValidationRule<T>[]
): void {
  const errors: Record<string, string[]> = {};

  for (const { field, rules: fieldRules } of rules) {
    const value = data[field];
    const fieldErrors: string[] = [];

    for (const rule of fieldRules) {
      const error = rule(value);
      if (error) fieldErrors.push(error);
    }

    if (fieldErrors.length > 0) {
      errors[field as string] = fieldErrors;
    }
  }

  if (Object.keys(errors).length > 0) {
    throw new ValidationError('Validation failed', errors);
  }
}
