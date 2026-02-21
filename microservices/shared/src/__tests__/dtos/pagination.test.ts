import { createPaginationMeta } from '../../dtos/pagination.dto';

describe('createPaginationMeta', () => {
  it('should calculate pagination meta correctly', () => {
    const meta = createPaginationMeta(100, 1, 10);
    expect(meta.total).toBe(100);
    expect(meta.page).toBe(1);
    expect(meta.limit).toBe(10);
    expect(meta.totalPages).toBe(10);
    expect(meta.hasNextPage).toBe(true);
    expect(meta.hasPreviousPage).toBe(false);
  });

  it('should handle last page', () => {
    const meta = createPaginationMeta(100, 10, 10);
    expect(meta.hasNextPage).toBe(false);
    expect(meta.hasPreviousPage).toBe(true);
  });

  it('should handle single page', () => {
    const meta = createPaginationMeta(5, 1, 10);
    expect(meta.totalPages).toBe(1);
    expect(meta.hasNextPage).toBe(false);
    expect(meta.hasPreviousPage).toBe(false);
  });

  it('should handle empty results', () => {
    const meta = createPaginationMeta(0, 1, 10);
    expect(meta.totalPages).toBe(0);
    expect(meta.hasNextPage).toBe(false);
  });
});
