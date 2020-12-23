﻿using Kubeless.Core.Interfaces;
using Kubeless.Core.Models;
using Microsoft.Extensions.Configuration;

namespace Kubeless.WebAPI.Utils
{
    public class FunctionFactory
    {
        private static readonly string ASSEMBLY_NAME = VariablesUtils.GetEnvVar("ASSEMBLY_NAME", "project");

        public static IFunction GetFunction(IConfiguration configuration)
        {
            var moduleName = configuration.GetNotNullConfiguration("MOD_NAME");
            var functionHandler = configuration.GetNotNullConfiguration("FUNC_HANDLER");

            return new CompiledFunction(moduleName, functionHandler, ASSEMBLY_NAME);
        }

        public static int GetFunctionTimeout(IConfiguration configuration)
        {
            var timeoutSeconds = configuration.GetNotNullConfiguration("FUNC_TIMEOUT");
            var milisecondsInSecond = 1000;

            return int.Parse(timeoutSeconds) * milisecondsInSecond;
        }
    }
}
