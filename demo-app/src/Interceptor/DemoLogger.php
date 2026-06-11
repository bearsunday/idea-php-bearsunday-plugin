<?php

declare(strict_types=1);

namespace MyVendor\MyProject\Interceptor;

use Ray\Aop\MethodInterceptor;
use Ray\Aop\MethodInvocation;

final class DemoLogger implements MethodInterceptor
{
    public function invoke(MethodInvocation $invocation): mixed
    {
        return $invocation->proceed();
    }
}
