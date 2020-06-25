package com.dsg.CostTImePlugin;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

/**
 * @author DSG* @Project ASMCostTime* @date 2020/6/22
 * @describe
 */
public class CostTimePlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.android.registerTransform(new CostTimeTransform())
    }
}
