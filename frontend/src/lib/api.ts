/**
 * 后端管理接口 fetch 薄封装（JSON + 统一错误对象）
 *
 * 后端错误形态：业务异常返回 { status, error, message }（如 400 参数错误 / 409 冲突），
 * 其余非 2xx 走框架默认错误体（message 可能为空，回退默认文案）。
 * 页面统一 catch ApiError 按 status + message 提示。
 */
export class ApiError extends Error {
  /** HTTP 状态码（如 400 / 409 / 500） */
  status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

async function parseErrorResponse(response: Response): Promise<never> {
  let message = `请求失败（HTTP ${response.status}）`;
  try {
    const data = await response.json();
    if (data && typeof data.message === 'string' && data.message) {
      message = data.message;
    }
  } catch {
    // 非 JSON 错误体（网关错误页等）→ 保留默认文案
  }
  throw new ApiError(response.status, message);
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, init);
  if (!response.ok) {
    await parseErrorResponse(response);
  }
  return (await response.json()) as T;
}

export function getJSON<T>(url: string): Promise<T> {
  return request<T>(url);
}

export function postJSON<T>(url: string, body?: unknown): Promise<T> {
  return request<T>(url, {
    method: 'POST',
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

/** multipart/form-data 上传（如知识库「上传文件」入库；浏览器自动设置 Content-Type 与 boundary） */
export function postForm<T>(url: string, form: FormData): Promise<T> {
  return request<T>(url, { method: 'POST', body: form });
}

export function del<T>(url: string): Promise<T> {
  return request<T>(url, { method: 'DELETE' });
}
