// Container: interface for operations that contain child operations.

import type { Operation } from '../../Operation';

export interface Container {
    getList(): Operation[];
}
