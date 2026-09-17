import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

/** shadcn / AI Elements 组件的类名合并工具 */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}
