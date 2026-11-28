package cn.edu.haut.campus.feature.degree.state

/** 一次性副作用；骨架切片目前只有返回上级，预留扩展。 */
sealed interface DegreeUiEffect {
    data object NavigateBack : DegreeUiEffect
}
